package cetushome.hook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Main control panel for the MixUnfold LSPosed module, built with the
 * miuix (HyperOS-style Compose) component library.
 *
 * Single scrollable page: the rotation master switch, the exclusion-list
 * entry (the app picker itself lives in AppPickerActivity), and the legacy
 * home-layout controls with the launcher restart. Every change is written
 * to Settings.System immediately (debounced) through the shared persistent
 * su shell — the rotation switch applies live via the system_server
 * ContentObserver, while the launcher layout keys still need "重启系统桌面".
 */
class MainActivity : ComponentActivity() {

    private var cols by mutableIntStateOf(3)
    private var landPages by mutableIntStateOf(3)
    private var padOpt by mutableStateOf(false)
    private var lockRot by mutableStateOf(true)
    private var freeRot by mutableStateOf(true)
    private var canWrite by mutableStateOf(false)
    private var excludeCount by mutableIntStateOf(0)
    private var status by mutableStateOf("")

    private val ui = Handler(Looper.getMainLooper())
    private var pendingWrite: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cols = read(MainHook.KEY_COLUMNS, 3)
        landPages = read(MainHook.KEY_LAND_PAGES, 3)
        padOpt = read(MainHook.KEY_PAD_OPT, 0) == 1
        lockRot = read(MainHook.KEY_LOCK_ROT, 1) == 1
        freeRot = read(MainHook.KEY_FREE_ROT, 1) == 1

        setContent {
            // ColorSchemeMode.System 跟随系统深浅色；Monet 动态取色留待后续
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = "MixUnfold",
                            subtitle = "MIX FOLD 1 桌面增强 · LSPosed 模块",
                        )
                    },
                ) { padding ->
                    Page(padding)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // the picker may have changed the exclusion key; the grant banner
        // may need to disappear after the user flipped the app-op
        canWrite = try {
            Settings.System.canWrite(this)
        } catch (t: Throwable) {
            false
        }
        excludeCount = readString(MainHook.KEY_ROT_EXCLUDE)
            ?.split(";")
            ?.count { it.isNotBlank() }
            ?: 0
    }

    // ---- page ------------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun Page(padding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (!canWrite) {
                item {
                    Card {
                        Text(
                            text = "尚未授予「修改系统设置」权限：改动将退回 root 逐条写入。" +
                                    "建议授权一次，之后所有改动自动保存。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { launchWriteSettingsPage() }) {
                            Text("① 点此授予「修改系统设置」权限")
                        }
                    }
                    SectionGap()
                }
            }

            item {
                SmallTitle(text = "内屏自由旋转")
                Card {
                    SwitchPreference(
                        checked = freeRot,
                        onCheckedChange = { freeRot = it; scheduleWrite() },
                        title = "总开关",
                        summary = "开启：内屏展开时所有应用跟随系统自动旋转" +
                                "（含原生锁定方向的应用），变更即时生效，无需重启；" +
                                "合盖后外屏强制竖屏。相机类应用始终不参与；" +
                                "排除清单中的应用保持原厂方向。关闭后全机恢复原厂方向行为。",
                    )
                }
                SectionGap()
            }

            item {
                SmallTitle(text = "排除清单")
                Card {
                    ArrowPreference(
                        title = "排除应用",
                        summary = "已排除 $excludeCount 个 · 勾选 = 该应用不旋转" +
                                "（保持原厂方向），带搜索，即时生效。",
                        onClick = {
                            startActivity(
                                Intent(this@MainActivity, AppPickerActivity::class.java),
                            )
                        },
                    )
                }
                SectionGap()
            }

            item {
                SmallTitle(text = "桌面布局（原有功能）")
                Card {
                    SliderPreference(
                        value = cols.toFloat(),
                        onValueChange = {
                            val v = it.roundToInt().coerceIn(2, 6)
                            if (v != cols) {
                                cols = v
                                scheduleWrite()
                            }
                        },
                        title = "桌面单页布局列数",
                        valueText = cols.toString(),
                        valueRange = 2f..6f,
                        steps = 3,
                    )
                    OverlaySpinnerPreference(
                        entries = listOf(
                            DropdownEntry(
                                items = listOf(
                                    DropdownItem(
                                        text = "两页",
                                        selected = landPages == 2,
                                        onClick = { updateLandPages(2) },
                                    ),
                                    DropdownItem(
                                        text = "三页",
                                        selected = landPages == 3,
                                        onClick = { updateLandPages(3) },
                                    ),
                                ),
                            ),
                        ),
                        title = "横屏页面组配置",
                        summary = "桌面不足 5 页时选三页，末组可能不满——预期行为。",
                    )
                    SwitchPreference(
                        checked = padOpt,
                        onCheckedChange = { padOpt = it; scheduleWrite() },
                        title = "屏幕外侧边距优化",
                        summary = "开启：竖屏外侧边距固定 120px；横屏按页面组适配——" +
                                "两页 280px、三页 0px（三页铺满、页间无缝）。\n" +
                                "关闭：采用小米原版边距方案（原生边距与页缝保留）。",
                    )
                    SwitchPreference(
                        checked = lockRot,
                        onCheckedChange = { lockRot = it; scheduleWrite() },
                        title = "锁屏允许横屏",
                        summary = "开启：内屏展开态的锁屏跟随系统自动旋转。\n" +
                                "小米未适配 cetus 横屏锁屏，横屏锁屏可能出现布局异常；" +
                                "关闭并重启系统界面即恢复原版。\n" +
                                "折叠态外屏锁屏始终为竖屏，不受此开关影响。",
                    )
                }
                SectionGap()
            }

            item {
                Card {
                    Button(
                        onClick = { restartLauncher() },
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Text("重启系统桌面")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (status.isEmpty()) {
                        "旋转开关与排除清单改动即时生效（首次改动会请求 root 授权）。\n" +
                                "桌面布局项改动后需点「重启系统桌面」。"
                    } else {
                        status
                    },
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SectionGap() {
        Spacer(Modifier.height(18.dp))
    }

    private fun updateLandPages(v: Int) {
        if (landPages != v) {
            landPages = v
            scheduleWrite()
        }
    }

    // ---- settings reads & writes -----------------------------------------------

    private fun read(key: String, def: Int): Int = try {
        Settings.System.getInt(contentResolver, key, def)
    } catch (t: Throwable) {
        def
    }

    private fun readString(key: String): String? = try {
        Settings.System.getString(contentResolver, key)
    } catch (t: Throwable) {
        null
    }

    private fun scheduleWrite() {
        pendingWrite?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            pendingWrite = null
            writeAllAsync()
        }
        pendingWrite = r
        ui.postDelayed(r, 400)
    }

    private fun writeAllAsync() {
        thread {
            val padLand = if (padOpt) (if (landPages == 3) 0 else 280) else 120
            // the exclusion key is owned by AppPickerActivity, never here
            val ok = SettingsWriter.put(this, MainHook.KEY_COLUMNS, cols) and
                    SettingsWriter.put(this, MainHook.KEY_PAD_OPT, if (padOpt) 1 else 0) and
                    SettingsWriter.put(this, MainHook.KEY_PAD_PORT, 120) and
                    SettingsWriter.put(this, MainHook.KEY_PAD_LAND, padLand) and
                    SettingsWriter.put(this, MainHook.KEY_LAND_PAGES, landPages) and
                    SettingsWriter.put(this, MainHook.KEY_LOCK_ROT, if (lockRot) 1 else 0) and
                    SettingsWriter.put(this, MainHook.KEY_FREE_ROT, if (freeRot) 1 else 0)
            if (!ok) {
                ui.post {
                    status = "写入失败 — 请授予「修改系统设置」权限或检查 root 授权"
                }
            }
        }
    }

    private fun launchWriteSettingsPage() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun restartLauncher() {
        status = "正在重启系统桌面…"
        thread {
            var ok = false
            var err: String? = null
            try {
                // Clean package-level stop + explicit relaunch. A raw
                // Process.killProcess() inside the launcher leaves AMS
                // state stale (skips fold/orientation callbacks) and
                // breaks rotation until a full reboot — force-stop is
                // the path that always initializes correctly.
                val p = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(p.outputStream)
                os.writeBytes("am force-stop com.miui.home\n")
                os.flush()
                // User-identical path: force-stop (same as app-info) then
                // a real HOME key event — the system resolves the default
                // home itself and reattaches to the native home task.
                // Never use `am start -n` here: it creates a task outside
                // the home role, which shows up as a stray 系统桌面 card
                // in recents.
                os.writeBytes("sleep 0.3\n")
                os.flush()
                os.writeBytes("input keyevent KEYCODE_HOME\n")
                os.flush()
                os.writeBytes("exit\n")
                os.flush()
                ok = p.waitFor() == 0
            } catch (t: Throwable) {
                err = t.toString()
            }
            val okF = ok
            val errF = err
            ui.post {
                if (okF) {
                    Toast.makeText(this, "系统桌面已重启", Toast.LENGTH_SHORT).show()
                    status = "已干净重启（等价于重启手机后的桌面初始化）。"
                } else {
                    status = "重启失败（root 未授权？）" + (errF?.let { "：$it" } ?: "")
                }
            }
        }
    }
}

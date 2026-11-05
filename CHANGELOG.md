# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
版本历史自 v1.6.2 起（更早无记录）；每个版本的完整改动见
[GitHub Releases](https://github.com/kirikryu/MixUnfold/releases)。

## [1.9.0] - 2026-11-05

- 安全修复：配置存储从 `Settings.System` 迁移到模块私有
  `SharedPreferences("mixunfold_prefs")`，从结构上消除 Mimosa 反复报告的
  跨文件污点链（`finding:5ed4ef6ce64178f2ca8f9225`：旧配置键对持
  `WRITE_SETTINGS` 的第三方应用可写，模块未获授权时经
  `SettingsWriter → RootWriter.exec("su")` 回退写入）。1.9.0 起模块 app
  是唯一写方（`MixPrefs`，`commit()` 同步落盘），launcher / SystemUI /
  system_server 只经 classic `XSharedPreferences` 只读（`HookPrefs`）；
  删除 `RootWriter`、`shellQuote`、`settingShellLine` 与
  `WRITE_SETTINGS` 声明，配置写入不再需要权限或 root。`ExcludeCodec`
  保留为迁移值/备份恢复值的输入校验，旧键只读回落、不再写回。
- 变更：升级兼容——覆盖安装首次打开 app 时把 1.8.x 的 8 个旧键快照进私有
  存储（`contains` 判定缺失键，不用默认值比较；缺失项写默认值，排除清单
  过白名单）。升级后尚未打开 app 时钩子只读回落旧键，行为与 1.8.4 一致；
  卸载重装以默认值工作（`firstInstallTime == lastUpdateTime` 判为非升级，
  旧键被忽略）。只清除应用数据（不卸载）可能重新导入旧键，属已知边界。
- 变更：即时性——`cetus_free_rot` / `cetus_free_rot_exclude` 在
  system_server 内 500ms 去抖 `reload()` 兜底，LSPosed 文件 watcher
  （`xposedsharedprefswatcher` 元数据）可用时置 dirty 跳过等待；真机
  （LSPosed 2.2.1）实测监听器注册成功、总开关关/开切换 `free-rot config
  reloaded` 即时出现、无需重启。列数/页数/边距/锁屏开关仍按原语义重启
  桌面/SystemUI 生效。
- 变更：跨进程读取实测——classic `XSharedPreferences` 直读
  `/data/data` 被 SELinux 拦截（hook 进程 `contains()` 恒 false），
  改用 LSPosed 新 XSharedPreferences（清单 `xposedsharedprefs` 元数据 +
  String 构造器，lspd root 守护读取，不依赖 prefs 文件世界可读位）；
  app 侧 `MODE_WORLD_READABLE` 被拒（探测一次后缓存、回落
  MODE_PRIVATE，无害）。launcher / SystemUI / system_server 三进程实测
  `readable=true`、`source=prefs`。
- 变更：安装通道——`adb install`（流式）产生的 APK 路径在本机
  LSPosed 2.2.1 守护开机扫描时报 ENOENT、system_server 静默不注入
  （未改动 1.8.4 基线对照复现，非本版回归）；处置为 `adb push` 后设备
  本地 `pm install -r` 再重启（设计文档第 10/15 节），实测恢复三进程
  注入。
- 变更：README 的 `adb shell settings put system cetus_*` 命令不再生效，
  配置只通过控制面板修改；控制面板移除「授予修改系统设置」入口与相关
  文案，写入失败提示改为检查应用存储空间。排除计数改用 `ExcludeCodec`，
  与实际生效清单一致。卸载重装配置归零、覆盖升级保留。
- 变更（开发）：新增 `PrefsKeys`（键/默认值唯一表）、`PrefsMigration`
  （contains 语义纯逻辑）、`MixPrefs`（app 写入 + 旧键迁移）、
  `HookPrefs`（只读 XSP 桥）与 `XSharedPreferences` 编译期 stub；
  `tools/run-tests.sh` 改为编译 `ExcludeCodec` / `PrefsKeys` /
  `PrefsMigration` / `WidgetSpan`，新增 `PrefsMigrationTest`（20 项），
  删除 shell 引号用例，全套 55 项。控制面板「重启系统桌面」保留原有
  `su` + 固定命令（全仓库唯一 exec，无配置值流入；取舍见设计文档第 15 节）。
- 安全验证：Mimosa deep 复扫（真机验证后终态）
  `scan-2026-11-05T08-12-48.840Z-17944a7abb92`（Seal
  `sha256:50ac27a5…f3f74dd0ea`）：**0 findings**，1.8.4 的唯一跨文件污点
  finding 消失；依赖风险 0。
- 已知行为：锁屏开关在首次解锁前（direct boot 窗口）按默认开处理——
  credential-protected prefs 在该窗口不可读，与 1.8.4 默认值一致；
  device-protected 存储路径已实现（`MixPrefs.USE_DEVICE_PROTECTED`
  常量）未默认启用。第二空间（多用户）路径已实现、未实测。

## [1.8.4] - 2026-11-05

- 修复：三列（及以下）列数模式下的小部件三类异常——① 原厂 4 列网格
  放置的 2x4 不显示：越界条目（`cellX+spanX` 超过列数）在
  `CellLayout.updateCellOccupiedMarks` 直写占用数组时抛越界异常，被
  `Launcher.bindAppWidget` 静默吞掉，条目从未上屏（数据库行完好）；②
  新加 2x3 偶发不显示：4 列坐标里合法的落点（cellX≥1）收窄后越界，
  同一异常路径；③ 日历"临近日程"等**添加不了**：MIUI 小部件选择器的
  `createItemInfo` 在"当前列数==最小列数"时用选择器 bundle 的原厂网格
  标注 span 覆盖像素映射结果，模块把最小列数钉到设置值使该门恒真，
  4 列标注的 spanX=4 被 `Workspace.acceptDrop` 直接拒绝放置。修复为
  两个装载/添加单点钳制：`ItemInfo.load` after（全部五类条目共用的解析
  汇流点，`spanX→列数` 上限、`cellX` 左移入界，**不写回数据库**，列数
  调回重启即恢复原尺寸）+ `MIUIWidgetCompat.createItemInfo` after
  （`spanX` 钳制，覆盖选择器拖出/预备/替换三个调用方）；MIUI 原生
  gadget/maml 与 Android 原生小部件一并覆盖（设计文档第 14 节，含
  4.39.12 反编译行号证据）；④ 长按「更换小部件」选择新部件**不生效**
  （4 列正常、<4 列均触发）：`MIUIWidgetReplaceHandler.canReplace` 要求
  旧部件运行时 span ≥ 新部件的原厂网格标注 span，收窄网格下 3≥4 恒假、
  在移除旧部件之前无声 abort——修复为标注值超出当前列数时折算到当前
  网格再比（钩子 19），不超界情形原厂语义逐位保留；新增 `WidgetSpan`
  纯函数与 21 项 JVM 回归（`tools/run-tests.sh` 全套 39 项）
- 变更（开发）：`tools/run-tests.sh` 加固——javac 改 `--release 8`（原先
  `-source/-target 8` 可链接 Java 9+ API，本地绿但 Android Java 8 运行时
  `NoSuchMethodError` 的假绿，实测 `List.of` 旧配置可编过、新配置正确
  拒绝）；统一 `-encoding UTF-8`；测试类自动发现（新增 `*Test.java` 无需
  改脚本）；失败也跑完全部测试再统一退出；`android.util.Log` shim 补全
  公开签名（新日志调用不再以 `NoSuchMethodError` 崩测试）；仓库根软链
  守卫（`rm -rf` 只可能在仓库根内执行）+ JDK/android.jar 缺失时人话报错；
  产物目录 `build/tests` → `build/jvm-tests`

## [1.8.3] - 2026-11-04

- 安全修复：排除清单解析与 root shell 写入注入加固（Mimosa 深度扫描
  `scan-2026-11-04T13-06-48.048Z-2c5dbccc5a50`，medium「疑似跨文件污点」
  经专家复核裁决为 confirmed 条件命令注入链）：`Settings.System` 键对持
  `WRITE_SETTINGS` 的第三方应用可写，毒化的 `cetus_free_rot_exclude`
  存量值经排除选择器回读重组后曾原样拼入常驻 su shell 的单引号包裹，
  单引号可闭合逃逸执行任意命令。现 parse / join / 写入三层均按
  `^[A-Za-z0-9_.]+$` 白名单过滤非法段（丢弃并记 logcat；新增公共
  `ExcludeCodec`，AppPickerActivity 与 MainHook.readExclude 统一接入）；
  su shell 命令行的键值统一经 POSIX 单引号转义（`RootWriter.shellQuote`），
  传入字符串无法闭合引号（su 的 stdin 必经 shell 解析，参数数组无法消除
  该层，故在 sink 转义兜底）。新增 `tools/run-tests.sh` JVM 回归测试
  （合法包名保留、注入段丢弃、引号文法不可闭合校验，15 项全过）
- 变更：Release 资产 APK 文件名中 versionCode 改用下划线接在
  versionName 后（如 `MixUnfold-v1.8.2_23-release.apk`，KernelSu 同款
  `v{versionName}_{versionCode}` 风格）；此前点连接的 `v1.8.2.23` 易被
  误读为四段版本号。本地 `./build.sh` 产物路径不变

## [1.8.2] - 2026-11-04

- 修复：桌面偶发**一次多出两页空白页**（如 3 页内容变 5 页）——补页钩子
  的 `insertNewScreen` 会触发原厂 `reorderScreens` 回调并同步内联重入
  补页逻辑，重入时新页尚未计入页数、仍读到奇数，于是再补一页（单次
  事件 +2）；另修同族"横屏三页模式误补 1 页且原厂清理不掉"：补页/补页
  保护以 app 上下文的方向判定"两页配对/三页免补"，该上下文在折叠切换后
  残留旧方向（第 8 节外屏误旋转的同一过期源）。现补页加重入锁、
  `reorderScreens` 触发改为 post 延迟；补页检查补挂到 `onConfigurationChanged`
  （Launcher 自消化旋转不触发 onResume，此前"横屏变奇数页 → 转竖屏"后不补页、
  要等锁屏解锁）；页数为奇且末页**有内容**时才补空白页，末尾空白页
  **一律不动**——原厂拖边建页的预备页（图标尚在拖拽中）与用户在编辑
  模式刚添加的空页曾被当作垃圾删掉，导致普通模式拖图标到末页右缘
  不弹新页、编辑模式加的页被删；偶数页保护否决门收窄到**仅编辑模式**
  生效（普通模式的末页移除属于原厂拖边建页自身流程，拦截会弄死
  手势）；全部方向/模式判定
  （边距/对齐模数/页宽/三页工作宽/三页吸附/补页/补页保护，共 9 处）
  统一改读实时桌面 Activity 的 configuration
- 已知行为：拖动图标新增页面使总页数变为奇数时，末尾的配对空白页
  **不会立即出现**——偶数页策略的触发点是锁屏解锁、旋转/折叠、离开
  桌面再返回，拖拽建页本身不触发；做一次上述操作空白页即补上
  （见 [FAQ](docs/faq.md#拖动图标新增页面后末尾空白页未立即出现)）
- 变更：Release 资产 APK 文件名带版本号（如
  `MixUnfold-v1.8.2.23-release.apk`，取自 AndroidManifest 的
  versionName/versionCode），避免 Release 页面跨版本出现多个同名
  `MixUnfold.apk`；本地 `./build.sh` 产物路径不变

## [1.8.1] - 2026-11-02

- 修复：MIUI 平行窗口应用内运行期方向请求（公众号文章页、详情页等
  `setRequestedOrientation` 竖屏锁定页）未被中和，把整个应用与显示钉死
  竖屏——引擎对多窗口记录的写点豁免是原生行为，未指定方向的记录此前只是
  巧合地跟随自由显示旋转。新增 E1 钩子双路桥接（通用机制，非单应用特例）：
  E1a 打开嵌入控制器读点的编译期旋转开关（cetus
  `config_embedded_activity_rotate=false`，惠及规则内嵌入应用）；E1b 对
  平行窗口记录（嵌入 TaskFragment 或 magic-window 窗口模式）中和运行期
  方向请求（legacy W1 的引擎模式孪生：总开关/排除清单/折叠态/size-compat
  门控与即时还原全部沿用，写点双写消除竖屏闪帧）
- 修复：排除应用选择器不显示新安装的应用——应用列表此前仅在页面创建时
  加载一次，选择器驻留后台期间新装的应用不会出现；现改为每次回到页面
  重新查询，并在页面存续期间监听安装/替换/卸载广播实时刷新；列表为空时
  提示检查「读取应用列表」权限
- 修复：排除应用选择器勾选/取消勾选不生效——root 兜底写入是异步落库
  （su 启动 + `settings` CLI），而写后立即回读只隔约 1ms，读到的一直是
  旧值，导致每次勾选都被判定"写入未生效"而回退；现改为轮询回读（直写
  路径同步落库不受影响），失败重试一次，且仅以最新一次写入的校验为准
- 修复：root 兜底写入未对值加引号——排除清单是分号分隔的包名列表，
  `;` 会被 sh 当作命令分隔符，多选时只有第一个包名被存入，其余被当作
  独立命令执行失败；现对 shell 值统一加单引号

## [1.8.0] - 2026-10-31

- 新增：内屏自由旋转（作用域新增 **系统框架**）——内屏展开时所有应用
  跟随系统自动旋转（含原生锁定方向的应用）。system_server 侧最终采用
  **引擎模式**：点亮休眠的 MIUI 折叠横屏引擎（`MiuiOrientationImpl`，
  写点中和 + 内外屏旋转设置原生分离 + 外屏默认锁竖屏/视频全屏放行），
  并将排除清单桥接为引擎按包允许表；手写钩子 W1–W5 保留为引擎不可用
  时的回退
- 新增：排除应用选择器（设置页整页可滚动、搜索过滤、勾选即时生效）与
  总开关「内屏自由旋转」
- 新增：配置项 `cetus_free_rot`、`cetus_free_rot_exclude`
- 已知代价：已参与旋转的应用运行期方向请求（播放器全屏、游戏切横屏）
  被吸收为跟随旋转，转回手持方向即可；相机与嵌入类应用始终排除
- 优化：登记时同步中和方向请求（引擎等效行为），消除"先竖屏闪一帧再横屏"
- 修复：启动预览窗按 `info.screenOrientation` 渲染导致的残留闪帧（W1 现双写
  `info` 与请求参数，等同引擎 case-1）；总开关关闭/排除清单时把已中和记录
  还原为原厂方向请求
- 新增：合盖强制竖屏（W4 显示级旋转锁）——cetus 原厂无生效的外屏锁定机制，
  自由旋转类应用合盖后会转横；现按"内屏自由、外屏锁定"实现
- 变更：嵌入/平行窗口应用跟随旋转（先前嵌入崩溃记录经机主更正为第三方
  模块配置冲突所致，撤销该排除）
- 优化：设置页搜索框紧贴应用列表，桌面布局区移至列表尾部
- 修复：冷启动残留竖屏闪帧（任务快照启动窗渲染竖屏截图所致，快照与
  `info` 无关；旋转不匹配的快照启动窗一律跳过，W5）
- 新增：合盖强制竖屏（W4 显示级旋转锁）——cetus 原厂无生效的外屏锁定机制，
  自由旋转类应用合盖后会转横；现按"内屏自由、外屏锁定"实现；显式横屏
  请求（视频全屏）放行
- 变更：MIUI 平行窗口（嵌入）应用跟随旋转（先前嵌入崩溃记录经机主更正为
  第三方模块配置冲突所致，撤销该排除；分屏/小窗仍保持原厂）
- 优化：排除应用列表拆分为二级界面，主页面收纳为单屏结构
- 优化：折叠判定叠加 ROM 全局折叠态查询，杜绝合盖切换期竞态改写

## [1.7.0] - 2026-10-31

- 新增：内屏展开态锁屏允许横竖旋转（新增作用域 **系统界面**，总开关
  「锁屏允许横屏」默认开）
- 新增：配置项 `cetus_home_lockscreen_rot`
- UI：配置页文字颜色改为主题自适应

## [1.6.5] - 2026-10-30

- 修复：配置页浅色背景文字不可读（文字颜色主题自适应 + WCAG AA 对比度保底）

## [1.6.4] - 2026-10-30

- 修复：折叠态实时判定与方向请求重发，修复外屏误旋转 / 内屏旋转失效
- 构建：签名与构建配置通用化（`signing.properties`、标准 debug 签名、
  CI Secrets 注入）；输出目录缺失时自动创建，修复干净克隆构建失败
- CI：校验值迁出 Release 正文，改随附件 `SHA256SUMS.txt` 分发；
  新增 gitleaks 全历史密钥扫描与 pre-commit 防线

## [1.6.2] - 2026-10-30（初始发布，Pre-release）

- 旋转解锁：展开内屏后桌面跟随系统横竖旋转
- 统一网格 + 外侧边距独立可调
- 两页反向对齐：页缝两侧列距一致
- 横屏三页模式（可选）
- 控制面板：滑杆调参 + 一键重启桌面

[1.8.1]: https://github.com/kirikryu/MixUnfold/compare/v1.8.0...v1.8.1
[1.8.0]: https://github.com/kirikryu/MixUnfold/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/kirikryu/MixUnfold/compare/v1.6.5...v1.7.0
[1.6.5]: https://github.com/kirikryu/MixUnfold/compare/v1.6.4...v1.6.5
[1.6.4]: https://github.com/kirikryu/MixUnfold/compare/v1.6.2...v1.6.4
[1.6.2]: https://github.com/kirikryu/MixUnfold/releases/tag/v1.6.2

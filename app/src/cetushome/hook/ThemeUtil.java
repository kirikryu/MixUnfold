package cetushome.hook;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.TypedValue;

/** Theme-adaptive text colors shared by the module activities: resolve from
 *  the activity theme (textColorPrimary / textColorSecondary / colorAccent),
 *  then get a WCAG AA contrast check (>= 4.5:1) against the resolved window
 *  background, so panels stay readable in both light and dark themes. */
final class ThemeUtil {

    private ThemeUtil() {
    }

    static int[] palette(Activity a) {
        int bg = backgroundProxy(a);
        return new int[]{
                ensureContrast(themeColor(a, android.R.attr.textColorPrimary,
                        0xFF1A1A1A), bg, 4.5),
                ensureContrast(themeColor(a, android.R.attr.textColorSecondary,
                        0xFF616161), bg, 4.5),
                ensureContrast(themeColor(a, android.R.attr.colorAccent,
                        0xFF1976D2), bg, 4.5)
        };
    }

    /** Resolve a theme color attribute to a concrete ARGB value, or {@code def}. */
    private static int themeColor(Activity a, int attr, int def) {
        TypedValue tv = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, tv, true)) {
            return def;
        }
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            try {
                // textColorPrimary/Secondary are usually color state lists;
                // the default state is what plain text renders with.
                ColorStateList csl = a.getResources().getColorStateList(
                        tv.resourceId, a.getTheme());
                return csl.getDefaultColor();
            } catch (Resources.NotFoundException e) {
                // attribute points at a drawable or other non-color resource
            }
        }
        return def;
    }

    /**
     * Best-effort window background color. When the theme paints the window
     * with a drawable instead of a flat color, invert the primary-text
     * lightness: the platform guarantees primary text contrasts with the
     * background, so dark text implies a light window and vice versa.
     */
    private static int backgroundProxy(Activity a) {
        int bg = themeColor(a, android.R.attr.windowBackground, 0);
        if (bg != 0) {
            return bg;
        }
        int primary = themeColor(a, android.R.attr.textColorPrimary, Color.BLACK);
        return luminance(primary) > 0.5 ? Color.BLACK : Color.WHITE;
    }

    /** Composite fg over bg — theme secondary colors carry alpha. */
    private static int flatten(int fg, int bg) {
        int alpha = Color.alpha(fg);
        if (alpha == 255) {
            return fg;
        }
        int r = (Color.red(fg) * alpha + Color.red(bg) * (255 - alpha)) / 255;
        int g = (Color.green(fg) * alpha + Color.green(bg) * (255 - alpha)) / 255;
        int b = (Color.blue(fg) * alpha + Color.blue(bg) * (255 - alpha)) / 255;
        return Color.rgb(r, g, b);
    }

    /** WCAG 2.x relative luminance. */
    private static double luminance(int c) {
        double r = Color.red(c) / 255.0;
        double g = Color.green(c) / 255.0;
        double b = Color.blue(c) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG contrast ratio between two ARGB colors (1.0 – 21.0). */
    private static double contrastRatio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Nudge a foreground color until it reaches {@code minRatio} against the
     * background, keeping its hue. Lowering HSV value scales luminance down
     * monotonically, so the light-background branch always converges; the
     * dark branch raises value, then drains saturation toward white (blues
     * at full value still cannot reach AA on black). Returns black/white if
     * the hue is hopeless.
     */
    private static int ensureContrast(int fg, int bg, double minRatio) {
        fg = flatten(fg, bg);
        if (contrastRatio(fg, bg) >= minRatio) {
            return fg;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(fg, hsv);
        if (luminance(bg) >= luminance(fg)) {
            while (hsv[2] > 0f && contrastRatio(fg, bg) < minRatio) {
                hsv[2] = Math.max(0f, hsv[2] - 0.02f);
                fg = Color.HSVToColor(hsv);
            }
            return contrastRatio(fg, bg) >= minRatio ? fg : Color.BLACK;
        }
        while (hsv[2] < 1f && contrastRatio(fg, bg) < minRatio) {
            hsv[2] = Math.min(1f, hsv[2] + 0.02f);
            fg = Color.HSVToColor(hsv);
        }
        while (hsv[1] > 0f && contrastRatio(fg, bg) < minRatio) {
            hsv[1] = Math.max(0f, hsv[1] - 0.02f);
            fg = Color.HSVToColor(hsv);
        }
        return contrastRatio(fg, bg) >= minRatio ? fg : Color.WHITE;
    }
}

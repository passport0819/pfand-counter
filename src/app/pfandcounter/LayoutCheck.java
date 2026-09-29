package app.pfandcounter;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Layout;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * Checks the screens in every language without looking at the screen: each layout is inflated in
 * that language and font size, measured at the phone's width, and every text is asked whether it
 * was cut off, runs past its box, or (on a button) breaks into a second line. Works with the phone
 * locked, since nothing is drawn.
 *
 * adb shell am start -n …/app.pfandcounter.MainActivity --ez layout_check true
 * adb logcat -s PfandLayout
 */
class LayoutCheck {
    static final String TAG = "PfandLayout";

    static void run(Context base, String[] languages) {
        int problems = 0;
        int width = base.getResources().getDisplayMetrics().widthPixels;
        float density = base.getResources().getDisplayMetrics().density;
        float[] scales = {1.0f, 1.3f};
        for (String lang : languages) {
            for (float scale : scales) {
                Configuration c = new Configuration(base.getResources().getConfiguration());
                c.setLocale(Locale.forLanguageTag(lang));
                c.fontScale = scale;
                Context ctx = new ContextThemeWrapper(base.createConfigurationContext(c), R.style.AppTheme);
                String where = lang + " x" + scale;
                problems += check(ctx, R.layout.main, width, where + " main");
                problems += check(ctx, R.layout.row, width, where + " row");
                // Dialogs keep a margin on both sides; 24 dp each is what the platform uses on phones.
                int inDialog = width - (int) (48 * density);
                problems += check(ctx, R.layout.dialog_new, inDialog, where + " dialog");
                // The crate window's three faces and the statistics page, filled as the app fills them.
                problems += measure(crates(ctx, 0), inDialog, where + " crates");
                problems += measure(crates(ctx, 1), inDialog, where + " crates-own");
                problems += measure(crates(ctx, 2), inDialog, where + " crates-help");
                problems += measure(stats(ctx), inDialog, where + " stats");
                problems += measure(sheetButtons(ctx), inDialog, where + " buttons");
            }
        }
        Log.i(TAG, "layout check done: " + problems + " problem(s) in " + languages.length
                + " languages at font scale 1.0 and 1.3");
    }

    private static int check(Context ctx, int layout, int width, String where) {
        View root = LayoutInflater.from(ctx).inflate(layout, null, false);
        fill(root, ctx);
        return measure(root, width, where);
    }

    /** The crate window: 0 main, 1 own pack form, 2 help. Nothing is written, nothing counted. */
    private static View crates(Context ctx, int face) {
        android.widget.LinearLayout host = new android.widget.LinearLayout(ctx);
        host.setOrientation(android.widget.LinearLayout.VERTICAL);
        CrateSheet sheet = new CrateSheet(ctx, new Store(ctx), host, null);
        if (face == 0) sheet.showMain();
        else if (face == 1) sheet.showForm();
        else sheet.showHelp();
        return host;
    }

    /** The full-width buttons of Settings and About, each with its picture, as the app makes them. */
    private static View sheetButtons(Context ctx) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int[][] buttons = {
                {R.drawable.ic_b_book, R.string.howto_open}, {R.drawable.ic_b_info, R.string.pfand_open},
                {R.drawable.ic_b_stats, R.string.stats_open}, {R.drawable.ic_h_shop, R.string.map_open},
                {R.drawable.ic_h_lock, R.string.privacy_open}, {R.drawable.ic_b_book, R.string.report_translation},
        };
        for (int[] b : buttons) {
            Button button = new Button(ctx);
            button.setAllCaps(false);
            button.setBackgroundResource(R.drawable.btn_accent);
            MainActivity.withIcon(ctx, button, b[0], ctx.getString(b[1]));
            wrap.addView(button);
        }
        // Settings shows six of them two to a row.
        int[][] tiles = {
                {R.drawable.ic_b_book, R.string.howto_open}, {R.drawable.ic_b_info, R.string.pfand_open},
                {R.drawable.ic_b_stats, R.string.stats_open}, {R.drawable.ic_h_scan, R.string.learned_open},
                {R.drawable.ic_b_ai, R.string.jev_settings}, {R.drawable.ic_b_info, R.string.settings_about},
        };
        for (int i = 0; i < tiles.length; i += 2) {
            Button a = new Button(ctx), b = new Button(ctx);
            for (int k = 0; k < 2; k++) {
                Button t = k == 0 ? a : b;
                t.setAllCaps(false);
                t.setBackgroundResource(R.drawable.btn_accent);
                MainActivity.withIcon(ctx, t, tiles[i + k][0], ctx.getString(tiles[i + k][1]));
            }
            wrap.addView(MainActivity.pair(ctx, a, b));
        }
        return wrap;
    }

    /** The statistics page with every row it can have, and large numbers. */
    private static View stats(Context ctx) {
        Stats s = new Stats();
        s.since = System.currentTimeMillis();
        s.count(true, false, 25, 1234);
        s.count(false, false, 15, 567);
        s.count(true, false, 8, 890);
        s.count(false, true, 150, 45);
        s.count(false, true, 75, 3);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        MainActivity.buildStats(ctx, wrap, s, 12345, new double[]{1234, 12.34}, true);
        return wrap;
    }

    private static int measure(View root, int width, String where) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        return walk(root, where);
    }

    /** Longest realistic content where the layout only holds a placeholder. */
    private static void fill(View root, Context ctx) {
        set(root, R.id.total, "123,45€");
        set(root, R.id.breakdown, "12 × 0,25  ·  8 × 0,15  ·  24 × 0,08  ·  2 × 1,50 " + ctx.getString(R.string.crate_tag));
        set(root, R.id.count, ctx.getResources().getQuantityString(R.plurals.items, 44, 44) + " · "
                + ctx.getResources().getQuantityString(R.plurals.crates, 12, 12));
        View share = root.findViewById(R.id.share);
        if (share != null) share.setVisibility(View.VISIBLE);
        set(root, R.id.row_qty, "12x");
        // The same pictures in front of the words as the app puts there, with the longest texts.
        icon(root, ctx, R.id.reset, R.drawable.ic_b_undo, R.string.btn_undo_reset);
        icon(root, ctx, R.id.guide, R.drawable.ic_b_help, R.string.btn_guide);
        icon(root, ctx, R.id.share, R.drawable.ic_b_share, R.string.share);
        icon(root, ctx, R.id.torch, R.drawable.ic_b_torch, R.string.torch);
        View sort = root.findViewById(R.id.sort);
        if (sort != null) {
            sort.setVisibility(View.VISIBLE);
            String a = ctx.getString(R.string.sort_newest), b = ctx.getString(R.string.sort_oldest);
            MainActivity.withIcon(ctx, (TextView) sort, R.drawable.ic_b_sort, a.length() >= b.length() ? a : b);
        }
        icon(root, ctx, R.id.dlg_ask_jev, R.drawable.ic_b_ai, R.string.jev_ask_again);
        icon(root, ctx, R.id.dlg_help, R.drawable.ic_b_help, R.string.g_open);
    }

    private static void icon(View root, Context ctx, int id, int icon, int text) {
        View v = root.findViewById(id);
        if (v instanceof TextView) MainActivity.withIcon(ctx, (TextView) v, icon, ctx.getString(text));
    }

    private static void set(View root, int id, String text) {
        View v = root.findViewById(id);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }

    private static int walk(View v, String where) {
        int n = 0;
        if (v.getVisibility() != View.VISIBLE) return 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += walk(g.getChildAt(i), where);
            return n;
        }
        if (!(v instanceof TextView)) return 0;
        TextView t = (TextView) v;
        Layout l = t.getLayout();
        if (l == null || t.getText().length() == 0) return 0;
        String name = "text";
        if (v.getId() != View.NO_ID) {
            try {
                name = v.getResources().getResourceEntryName(v.getId());
            } catch (android.content.res.Resources.NotFoundException generated) {
                // an id made at run time (View.generateViewId) has no name
            }
        }
        int room = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
        String issue = null;
        for (int i = 0; i < l.getLineCount(); i++) {
            if (l.getEllipsisCount(i) > 0) issue = "cut off";
            else if (l.getLineMax(i) > room + 1) issue = "runs past its box";
        }
        // Names of products may be cut (they end in "…" on purpose); everything else must fit.
        if ("cut off".equals(issue) && (name.equals("row_name") || name.equals("row_sub"))) issue = null;
        // The deposit choices in the new-barcode window are full-width rows: two lines there are fine.
        boolean row = name.matches("dlg_(\\d+|crate)");
        // A button written in two lines on purpose (a pack and its sum) may have exactly those.
        int meant = 1;
        for (int i = 0; i < t.getText().length(); i++) if (t.getText().charAt(i) == '\n') meant++;
        // A radio button's label may wrap like any line of text in a list.
        if (t instanceof android.widget.CompoundButton) meant = Integer.MAX_VALUE;
        // A half-width button in Settings may take two lines, not three.
        if (MainActivity.TILE.equals(t.getTag())) meant = 2;
        if (issue == null && t instanceof Button && !row && l.getLineCount() > meant) issue = "breaks into " + l.getLineCount() + " lines";
        if (issue == null && t.getHeight() < l.getHeight() + t.getTotalPaddingTop() + t.getTotalPaddingBottom() - 1) {
            issue = "taller than its box";
        }
        // A label that had to shrink below 12 dp as seen on the screen (small print) is hard to read.
        float dp = t.getTextSize() / t.getResources().getDisplayMetrics().density;
        if (issue == null && t.getAutoSizeTextType() != TextView.AUTO_SIZE_TEXT_TYPE_NONE && dp < 12) {
            issue = String.format(Locale.ROOT, "shrinks to %.1f dp", dp);
        }
        if (issue == null) return 0;
        Log.w(TAG, where + ": " + name + " \"" + t.getText() + "\" " + issue);
        return 1;
    }
}

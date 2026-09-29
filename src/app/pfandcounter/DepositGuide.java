package app.pfandcounter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * "Help me decide": walks through the German deposit rules one picture question at a time,
 * for a barcode the app does not know yet. Two or three taps end in one amount, with the reason.
 *
 * The law behind the answers, checked 22.09.2026 at gesetze-im-internet.de/verpackdg/__46.html
 * (§ 46 VerpackDG, in force since 12.08.2026, replacing § 31 VerpackG with the same content):
 *  - every single-use drink container of 0.1-3 l carries at least 0.25 EUR and must be marked
 *    "dauerhaft, deutlich lesbar und an gut sichtbarer Stelle als pfandpflichtig";
 *  - cartons (block, gable, cylinder) and pouches carry none;
 *  - wine, sparkling wine, spirits, milk, juice and nectar carry none — EXCEPT in single-use
 *    plastic bottles and in cans, where they carry 0.25 like everything else;
 *  - containers not meant for sale in Germany carry none (an import without the mark).
 * Reusable amounts (0.08 / 0.15) are custom, not law — the guide says "usually".
 */
final class DepositGuide {

    interface Listener {
        /** The user settled on an amount (a DepositRules constant, NONE included). */
        void onChosen(int cents);
        /** The user stepped back out of the first question. */
        void onLeave();
    }

    private static final class Option {
        final int icon, label, detail;
        final Step next;
        Option(int icon, int label, int detail, Step next) {
            this.icon = icon; this.label = label; this.detail = detail; this.next = next;
        }
    }

    static final class Step {
        final int title, hint;
        final Option[] options;
        final int cents, reason;          // set for a result, options == null
        Step(int title, int hint, Option... options) {
            this.title = title; this.hint = hint; this.options = options;
            this.cents = 0; this.reason = 0;
        }
        Step(int cents, int reason) {
            this.title = 0; this.hint = 0; this.options = null;
            this.cents = cents; this.reason = reason;
        }
    }

    /** The whole tree. Every path ends in a result — checked by DepositGuide.paths(). */
    static final Step ROOT;
    /** The jar question alone, for a barcode Open Food Facts already calls a dairy jar. */
    static final Step JAR;
    static {
        Step can = new Step(DepositRules.SINGLE_USE, R.string.g_why_can);
        Step carton = new Step(DepositRules.NONE, R.string.g_why_carton);

        Step plasticDpg = new Step(DepositRules.SINGLE_USE, R.string.g_why_plastic_dpg);
        Step plasticReuse = new Step(DepositRules.REUSABLE, R.string.g_why_plastic_reuse);
        Step plasticNone = new Step(DepositRules.NONE, R.string.g_why_plastic_none);
        Step plastic = new Step(R.string.g_q_plastic, R.string.g_h_plastic,
                new Option(R.drawable.ic_g_logo, R.string.g_o_dpg, R.string.g_d_dpg, plasticDpg),
                new Option(R.drawable.ic_g_reuse, R.string.g_o_reuse, R.string.g_d_reuse_plastic, plasticReuse),
                new Option(R.drawable.ic_g_none, R.string.g_o_nomark, R.string.g_d_nomark_plastic, plasticNone));

        Step glassDpg = new Step(DepositRules.SINGLE_USE, R.string.g_why_glass_dpg);
        Step swing = new Step(DepositRules.REUSABLE, R.string.g_why_swing);
        Step beer = new Step(DepositRules.REUSABLE_SMALL, R.string.g_why_beer);
        Step glassReuse = new Step(DepositRules.REUSABLE, R.string.g_why_glass_reuse);
        Step glassNone = new Step(DepositRules.NONE, R.string.g_why_glass_none);
        Step glass = new Step(R.string.g_q_glass, R.string.g_h_glass,
                // The logo first: a marked one-way beer bottle must not end up as 0.08.
                new Option(R.drawable.ic_g_logo, R.string.g_o_dpg, R.string.g_d_dpg_glass, glassDpg),
                new Option(R.drawable.ic_g_swing, R.string.g_o_swing, R.string.g_d_swing, swing),
                new Option(R.drawable.ic_g_beer, R.string.g_o_beer, R.string.g_d_beer, beer),
                new Option(R.drawable.ic_g_reuse, R.string.g_o_reuse, R.string.g_d_reuse_glass, glassReuse),
                new Option(R.drawable.ic_g_none, R.string.g_o_glass_none, R.string.g_d_glass_none, glassNone));

        // Yogurt, cream and quark jars: no law, only the dairies' pool glass (see DairyJarCheck).
        Step jarReuse = new Step(DepositRules.REUSABLE, R.string.g_why_jar_reuse);
        Step jarNone = new Step(DepositRules.NONE, R.string.g_why_jar_none);
        Step jar = JAR = new Step(R.string.g_q_jar, R.string.g_h_jar,
                new Option(R.drawable.ic_g_reuse, R.string.g_o_jar_reuse, R.string.g_d_jar_reuse, jarReuse),
                new Option(R.drawable.ic_g_none, R.string.g_o_jar_none, R.string.g_d_jar_none, jarNone));

        ROOT = new Step(R.string.g_q_material, R.string.g_h_material,
                new Option(R.drawable.ic_g_can, R.string.g_o_can, R.string.g_d_can, can),
                new Option(R.drawable.ic_g_plastic, R.string.g_o_plastic, R.string.g_d_plastic, plastic),
                new Option(R.drawable.ic_g_glass, R.string.g_o_glass, R.string.g_d_glass, glass),
                new Option(R.drawable.ic_g_jar, R.string.g_o_jar, R.string.g_d_jar, jar),
                new Option(R.drawable.ic_g_carton, R.string.g_o_carton, R.string.g_d_carton, carton));
    }

    /** Every step of the tree once, questions and answers (for LayoutCheck). */
    static List<Step> steps() {
        List<Step> all = new ArrayList<>();
        Deque<Step> todo = new ArrayDeque<>();
        todo.add(ROOT);
        todo.add(JAR);
        while (!todo.isEmpty()) {
            Step s = todo.poll();
            if (all.contains(s)) continue;
            all.add(s);
            if (s.options != null) for (Option o : s.options) todo.add(o.next);
        }
        return all;
    }

    private final Context context;
    private final LinearLayout host;
    private final Listener listener;
    private final Deque<Step> trail = new ArrayDeque<>();

    DepositGuide(Context context, LinearLayout host, Listener listener) {
        this.context = context;
        this.host = host;
        this.listener = listener;
    }

    void start() {
        start(ROOT);
    }

    /** Starts at a later question; "‹ Back" from there leaves the guide. */
    void start(Step first) {
        trail.clear();
        show(first);
    }

    private void back() {
        trail.pop();
        if (trail.isEmpty()) listener.onLeave();
        else show(trail.pop());
    }

    private void show(Step step) {
        trail.push(step);
        host.removeAllViews();
        if (step.options != null) showQuestion(step);
        else showResult(step);

        TextView back = text(R.string.g_back, 14, R.color.text_dim);
        MainActivity.speakPlain(back);
        back.setPadding(0, dp(14), 0, dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { back(); }
        });
        host.addView(back);
    }

    private void showQuestion(Step step) {
        host.addView(text(step.title, 17, R.color.text));
        TextView hint = text(step.hint, 13, R.color.text_dim);
        hint.setPadding(0, dp(2), 0, dp(10));
        host.addView(hint);
        for (final Option o : step.options) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.btn_bg);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            ImageView icon = new ImageView(context);
            icon.setImageResource(o.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
            ip.setMarginEnd(dp(12));
            row.addView(icon, ip);

            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView label = text(o.label, 15, R.color.text);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            words.addView(label);
            words.addView(text(o.detail, 12, R.color.text_dim));
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            row.setClickable(true);
            // Read out with its explanation: the picture alone tells a screen-reader user nothing.
            row.setContentDescription(context.getString(o.label) + ". " + context.getString(o.detail));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { show(o.next); }
            });
            host.addView(row);
        }
    }

    private void showResult(final Step step) {
        TextView amount = new TextView(context);
        amount.setText(step.cents == DepositRules.NONE
                ? context.getString(R.string.g_r_none) : MainActivity.money(step.cents));
        amount.setTextSize(40);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setTextColor(context.getColor(step.cents == DepositRules.NONE ? R.color.text_dim : R.color.accent));
        host.addView(amount);

        TextView reason = text(step.reason, 14, R.color.text);
        reason.setPadding(0, dp(6), 0, dp(14));
        host.addView(reason);

        Button use = new Button(context);
        use.setAllCaps(false);
        use.setTextSize(16);
        use.setBackgroundResource(R.drawable.btn_accent);
        use.setTextColor(context.getColor(R.color.text));
        use.setText(step.cents == DepositRules.NONE
                ? context.getString(R.string.g_use_none)
                : context.getString(R.string.g_use_fmt, MainActivity.money(step.cents)));
        use.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onChosen(step.cents); }
        });
        host.addView(use, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (step.cents == DepositRules.REUSABLE || step.cents == DepositRules.REUSABLE_SMALL) {
            TextView custom = text(R.string.g_custom_note, 11, R.color.text_dim);
            custom.setPadding(0, dp(10), 0, 0);
            host.addView(custom);
        }
    }

    private TextView text(int res, int sp, int color) {
        TextView t = new TextView(context);
        t.setText(res);
        t.setTextSize(sp);
        t.setTextColor(context.getColor(color));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** Every path from the first question to a result, as "label > label = cents". For tests. */
    static java.util.List<String> paths(android.content.res.Resources r) {
        java.util.List<String> out = new java.util.ArrayList<>();
        walk(ROOT, "", r, out);
        return out;
    }

    private static void walk(Step s, String prefix, android.content.res.Resources r, java.util.List<String> out) {
        if (s.options == null) { out.add(prefix + " = " + s.cents); return; }
        for (Option o : s.options) walk(o.next, prefix + " > " + r.getString(o.label), r, out);
    }
}

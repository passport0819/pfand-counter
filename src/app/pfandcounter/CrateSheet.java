package app.pfandcounter;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything behind "+Crate", in one window whose content is swapped (a window opened from a
 * window blocked the input before, see MainActivity.openLater): the crate deposit, one empty
 * crate, a full crate with its bottles in one tap, the user's own packs, and "help me decide".
 *
 * Sources, read 28.09.2026:
 *  - Wikipedia „Getränkekiste“ (de.wikipedia.org/wiki/Getränkekiste): crates carry their own
 *    deposit, "in der Regel mit 1,50 €"; a crate that splits into halves pays half per half
 *    (0,75 €); beer 20 × 0,5 l and 24 × 0,33 l; water 12 × 0,75 l, 6 × 1,5 l and 12 × 1 l PET;
 *    juice 6 × 0,75 l and 6 × 1 l.
 *  - Verbraucherzentrale „Mehrweg oder Einweg: Wichtige Unterschiede beim Pfand“: some crates
 *    hold single-use bottles, often with the PET-Cycle mark; a broken bottle does not let the
 *    shop refuse the crate, only that bottle's deposit may be kept back.
 * The bottle amounts of the presets are the usual reusable ones the app uses everywhere
 * (0,08 beer, 0,15 the rest); "Deposit per bottle" overrides them.
 */
final class CrateSheet {

    interface Listener {
        void onEmptyCrate(int crateCents);
        void onFullCrate(Pack pack, int bottleCents, int crateCents);
    }

    /** A crate of bottles: a preset from the sources above, or one the user saved. */
    static final class Pack {
        final String key;
        final String name;
        final int bottles;
        /** Litres per bottle, 0 when not known (the user's own packs). */
        final double litres;
        final int bottleCents;
        /** The user's own crate amount; -1 for presets, which take the chosen crate deposit. */
        final int crateCents;

        Pack(String key, String name, int bottles, double litres, int bottleCents, int crateCents) {
            this.key = key;
            this.name = name;
            this.bottles = bottles;
            this.litres = litres;
            this.bottleCents = bottleCents;
            this.crateCents = crateCents;
        }

        boolean own() {
            return crateCents >= 0;
        }

        /** "Beer · 20 × 0,5 l", in the reader's decimal writing. An own pack has no size, so just its
         *  name: the line below already starts with the bottle count. */
        String label() {
            if (litres <= 0) return name;
            NumberFormat f = NumberFormat.getNumberInstance(Locale.getDefault());
            f.setMaximumFractionDigits(2);
            return name + " · " + bottles + " × " + f.format(litres) + " l";
        }
    }

    /** The crate amount chosen last, in cents. */
    static final String CRATE_FILE = "crate.txt";
    /** The user's own packs, as a JSON array. */
    static final String PACKS_FILE = "packs.json";
    /** Upper bound for a typed amount: no crate costs more than this. */
    private static final int MAX_CENTS = 5000;
    private static final int MAX_BOTTLES = 60;
    private static final int[] BOTTLE_VALUES = {DepositRules.REUSABLE_SMALL, DepositRules.REUSABLE,
            DepositRules.SINGLE_USE};

    private final Context context;
    private final Store store;
    private final LinearLayout host;
    private final Listener listener;
    private final List<Pack> own = new ArrayList<Pack>();
    private int crateCents;
    /** 0 = each pack's own bottle amount, else the amount picked under "Deposit per bottle". */
    private int bottleOverride;
    private boolean crateValid = true;
    private Button emptyButton;
    private LinearLayout rows;

    CrateSheet(Context context, Store store, LinearLayout host, Listener listener) {
        this.context = context;
        this.store = store;
        this.host = host;
        this.listener = listener;
        crateCents = crateValue(store);
        loadOwn();
    }

    /** The crate amount chosen last; 1,50 € until the user picks another. */
    static int crateValue(Store store) {
        try {
            int c = Integer.parseInt(String.valueOf(store.read(CRATE_FILE)).trim());
            if (c >= 0 && c <= MAX_CENTS) return c;
        } catch (NumberFormatException ignored) {
            // nothing chosen yet
        }
        return DepositRules.CRATE;
    }

    List<Pack> presets() {
        List<Pack> p = new ArrayList<Pack>();
        p.add(new Pack("beer20", context.getString(R.string.c_beer), 20, 0.5, DepositRules.REUSABLE_SMALL, -1));
        p.add(new Pack("beer24", context.getString(R.string.c_beer), 24, 0.33, DepositRules.REUSABLE_SMALL, -1));
        p.add(new Pack("water12", context.getString(R.string.c_water), 12, 0.75, DepositRules.REUSABLE, -1));
        p.add(new Pack("water12pet", context.getString(R.string.c_water_pet), 12, 1.0, DepositRules.REUSABLE, -1));
        p.add(new Pack("water6", context.getString(R.string.c_water), 6, 1.5, DepositRules.REUSABLE, -1));
        p.add(new Pack("juice6s", context.getString(R.string.c_juice), 6, 0.75, DepositRules.REUSABLE, -1));
        p.add(new Pack("juice6", context.getString(R.string.c_juice), 6, 1.0, DepositRules.REUSABLE, -1));
        return p;
    }

    // --- the main panel -----------------------------------------------------

    void showMain() {
        host.removeAllViews();
        host.addView(heading(R.string.c_value));
        final RadioGroup values = new RadioGroup(context);
        final RadioButton usual = radio(context.getString(R.string.c_value_usual, MainActivity.money(DepositRules.CRATE)));
        final RadioButton half = radio(context.getString(R.string.c_value_half, MainActivity.money(DepositRules.HALF_CRATE)));
        final RadioButton other = radio(context.getString(R.string.c_value_other));
        values.addView(usual);
        values.addView(half);
        values.addView(other);
        host.addView(values);
        final EditText typed = amountField();
        typed.setHint(R.string.c_value_hint);
        host.addView(typed);
        if (crateCents == DepositRules.CRATE) values.check(usual.getId());
        else if (crateCents == DepositRules.HALF_CRATE) values.check(half.getId());
        else {
            values.check(other.getId());
            typed.setText(plain(crateCents));
        }
        typed.setVisibility(values.getCheckedRadioButtonId() == other.getId() ? View.VISIBLE : View.GONE);
        values.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int id) {
                typed.setVisibility(id == other.getId() ? View.VISIBLE : View.GONE);
                if (id == usual.getId()) setCrate(DepositRules.CRATE);
                else if (id == half.getId()) setCrate(DepositRules.HALF_CRATE);
                else {
                    typed.requestFocus();
                    setCrate(cents(typed.getText().toString()));
                }
            }
        });
        typed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (values.getCheckedRadioButtonId() == other.getId()) setCrate(cents(s.toString()));
            }
        });

        emptyButton = button(R.drawable.btn_accent);
        emptyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (crateValid) listener.onEmptyCrate(crateCents);
            }
        });
        host.addView(emptyButton, full(8));

        host.addView(heading(R.string.c_full));
        host.addView(dim(R.string.c_full_hint));
        host.addView(small(R.string.c_bottle));
        final int[] choices = {0, DepositRules.REUSABLE_SMALL, DepositRules.REUSABLE, DepositRules.SINGLE_USE};
        String[] names = {context.getString(R.string.c_bottle_listed), MainActivity.money(choices[1]),
                MainActivity.money(choices[2]), MainActivity.money(choices[3])};
        int now = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i] == bottleOverride) now = i;
        host.addView(segments(names, now, new Picked() {
            @Override public void picked(int i) {
                bottleOverride = choices[i];
                fillRows();
            }
        }), full(4));

        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        host.addView(rows, full(4));
        fillRows();

        Button add = button(R.drawable.btn_bg);
        add.setText(R.string.c_own_add);
        // One line in every language: long words shrink a little instead of wrapping.
        add.setMaxLines(1);
        add.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showForm();
            }
        });
        host.addView(add, full(2));

        Button help = button(R.drawable.btn_accent);
        MainActivity.withIcon(context, help, R.drawable.ic_b_help, context.getString(R.string.c_help));
        help.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showHelp();
            }
        });
        host.addView(help, full(14));

        TextView code = dim(R.string.c_barcode_hint);
        code.setPadding(0, dp(12), 0, dp(4));
        host.addView(code);
        updateLabels();
    }

    private void setCrate(int cents) {
        crateValid = cents >= 0 && cents <= MAX_CENTS;
        if (crateValid) {
            crateCents = cents;
            store.write(CRATE_FILE, String.valueOf(cents));
        }
        updateLabels();
    }

    private void updateLabels() {
        if (emptyButton == null) return;
        emptyButton.setEnabled(crateValid && crateCents > 0);
        emptyButton.setText(crateValid ? context.getString(R.string.c_empty, MainActivity.money(crateCents))
                : context.getString(R.string.c_value_invalid));
        fillRows();
    }

    /** One row per pack: what goes in and what it is worth, counted with one tap. */
    private void fillRows() {
        if (rows == null) return;
        rows.removeAllViews();
        List<Pack> all = presets();
        all.addAll(own);
        for (final Pack p : all) {
            final int bottle = bottleOverride > 0 ? bottleOverride : p.bottleCents;
            final int crate = p.own() ? p.crateCents : crateCents;
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            Button b = button(R.drawable.btn_bg);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            String sum = p.bottles + " × " + MainActivity.money(bottle)
                    + (crate > 0 ? " + " + MainActivity.money(crate) : "")
                    + " = " + MainActivity.money(p.bottles * bottle + Math.max(0, crate));
            b.setText(p.label() + "\n" + sum);
            b.setEnabled(p.own() || crateValid);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    listener.onFullCrate(p, bottle, crate);
                }
            });
            line.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (p.own()) {
                final Button remove = button(R.drawable.btn_bg);
                remove.setText("✕");
                remove.setContentDescription(context.getString(R.string.c_own_delete) + ": " + p.name);
                remove.setOnClickListener(new View.OnClickListener() {
                    // First tap asks, second tap deletes: no window over the window.
                    @Override public void onClick(View v) {
                        if (remove.getTag() == null) {
                            remove.setTag(Boolean.TRUE);
                            remove.setText(R.string.c_own_delete);
                            remove.setBackgroundResource(R.drawable.btn_bad);
                        } else {
                            own.remove(p);
                            saveOwn();
                            fillRows();
                        }
                    }
                });
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.setMarginStart(dp(6));
                line.addView(remove, rp);
            }
            rows.addView(line, full(6));
        }
    }

    // --- own packs ----------------------------------------------------------

    void showForm() {
        host.removeAllViews();
        host.addView(title(R.string.c_own_title));
        final EditText name = new EditText(context);
        name.setHint(R.string.c_own_name);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        host.addView(name, full(0));
        final EditText count = new EditText(context);
        count.setHint(R.string.c_own_count);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        host.addView(count, full(0));
        host.addView(small(R.string.c_bottle));
        final int[] bottlePick = {1};
        String[] names = new String[BOTTLE_VALUES.length];
        for (int i = 0; i < names.length; i++) names[i] = MainActivity.money(BOTTLE_VALUES[i]);
        host.addView(segments(names, bottlePick[0], new Picked() {
            @Override public void picked(int i) {
                bottlePick[0] = i;
            }
        }), full(4));
        host.addView(small(R.string.c_own_crate));
        final EditText crate = amountField();
        crate.setText(plain(crateCents));
        host.addView(crate, full(0));
        final TextView problem = dim(R.string.c_own_invalid);
        problem.setTextColor(context.getColor(R.color.bad));
        problem.setVisibility(View.GONE);
        host.addView(problem);

        Button save = button(R.drawable.btn_accent);
        save.setText(R.string.save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = name.getText().toString().trim();
                int bottles;
                try {
                    bottles = Integer.parseInt(count.getText().toString().trim());
                } catch (NumberFormatException e) {
                    bottles = 0;
                }
                int c = cents(crate.getText().toString());
                if (n.isEmpty() || n.length() > 40 || bottles < 1 || bottles > MAX_BOTTLES || c < 0 || c > MAX_CENTS) {
                    problem.setVisibility(View.VISIBLE);
                    return;
                }
                int bottle = BOTTLE_VALUES[bottlePick[0]];
                own.add(new Pack("own" + System.currentTimeMillis(), n, bottles, 0, bottle, c));
                saveOwn();
                showMain();
            }
        });
        host.addView(save, full(10));
        host.addView(back());
    }

    private void loadOwn() {
        String raw = store.read(PACKS_FILE);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                own.add(new Pack(o.getString("key"), o.getString("name"), o.getInt("n"), 0,
                        o.getInt("b"), o.getInt("c")));
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "packs.json unreadable, starting without own packs", e);
        }
    }

    private void saveOwn() {
        JSONArray arr = new JSONArray();
        try {
            for (Pack p : own) {
                JSONObject o = new JSONObject();
                o.put("key", p.key);
                o.put("name", p.name);
                o.put("n", p.bottles);
                o.put("b", p.bottleCents);
                o.put("c", p.crateCents);
                arr.put(o);
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "cannot build packs.json", e);
            return;
        }
        store.write(PACKS_FILE, arr.toString());
    }

    // --- help me decide -------------------------------------------------------

    /**
     * What can honestly be said: a crate carries no mark that tells its deposit, so the one
     * question is whether it splits in two. Then what the sources say about the bottles in it.
     */
    void showHelp() {
        host.removeAllViews();
        host.addView(title(R.string.c_h_title));
        host.addView(dim(R.string.c_h_intro));
        TextView q = title(R.string.c_h_q);
        q.setPadding(0, dp(12), 0, dp(6));
        host.addView(q);
        host.addView(option(R.string.c_h_one, R.string.c_h_one_d, DepositRules.CRATE, R.string.c_h_one_why));
        host.addView(option(R.string.c_h_split, R.string.c_h_split_d, DepositRules.HALF_CRATE, R.string.c_h_split_why));
        host.addView(card(R.drawable.ic_g_logo, R.string.c_h_single_t, R.string.c_h_single));
        host.addView(card(R.drawable.ic_g_crate, R.string.c_h_broken_t, R.string.c_h_broken));
        TextView source = dim(R.string.c_h_source);
        source.setTextColor(context.getColor(R.color.accent));
        source.setPadding(0, dp(4), 0, 0);
        host.addView(source);
        host.addView(back());
    }

    private View option(int label, int detail, final int cents, final int why) {
        LinearLayout row = card(R.drawable.ic_g_crate, label, detail);
        row.setClickable(true);
        row.setContentDescription(context.getString(label) + ". " + context.getString(detail));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showAnswer(cents, why);
            }
        });
        return row;
    }

    private void showAnswer(final int cents, int why) {
        host.removeAllViews();
        TextView amount = new TextView(context);
        amount.setText(MainActivity.money(cents));
        amount.setTextSize(40);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setTextColor(context.getColor(R.color.accent));
        host.addView(amount);
        TextView reason = text(why, 14, R.color.text);
        reason.setPadding(0, dp(6), 0, dp(14));
        host.addView(reason);
        Button use = button(R.drawable.btn_accent);
        use.setText(context.getString(R.string.c_h_use, MainActivity.money(cents)));
        use.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setCrate(cents);
                showMain();
            }
        });
        host.addView(use, full(0));
        TextView note = dim(R.string.c_h_note);
        note.setPadding(0, dp(10), 0, 0);
        host.addView(note);
        TextView back = text(R.string.g_back, 14, R.color.text_dim);
        MainActivity.speakPlain(back);
        back.setPadding(0, dp(14), 0, dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showHelp();
            }
        });
        host.addView(back);
    }

    // --- small helpers --------------------------------------------------------

    private interface Picked {
        void picked(int index);
    }

    /**
     * A row of equal buttons of which one is picked (it gets the ring). Radio buttons side by
     * side broke into several lines in long languages; these shrink their words instead.
     */
    private LinearLayout segments(String[] names, int picked, final Picked listener) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Shrunk words sit lower on their baseline; aligned by it, a button slid out of the row.
        row.setBaselineAligned(false);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button b = new Button(context);
            b.setAllCaps(false);
            b.setText(names[i]);
            b.setTextColor(context.getColor(R.color.text));
            b.setMaxLines(1);
            b.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            b.setPadding(dp(4), dp(10), dp(4), dp(10));
            // A word gets more room than an amount such as "0,25€".
            float weight = names[i].length() > 6 ? 1.7f : 1f;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
            lp.setMarginEnd(i < names.length - 1 ? dp(4) : 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mark(row, index);
                    listener.picked(index);
                }
            });
            row.addView(b, lp);
        }
        mark(row, picked);
        return row;
    }

    private void mark(LinearLayout row, int picked) {
        for (int i = 0; i < row.getChildCount(); i++) {
            Button b = (Button) row.getChildAt(i);
            b.setBackgroundResource(i == picked ? R.drawable.btn_picked : R.drawable.btn_bg);
            b.setTypeface(i == picked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setSelected(i == picked);
        }
    }

    /** "1,50" → 150; "" or nonsense → -1. Comma or point, up to two decimals. */
    static int cents(String typed) {
        String t = typed == null ? "" : typed.trim().replace('€', ' ').trim().replace(',', '.');
        if (!t.matches("\\d{1,3}(\\.\\d{0,2})?")) return -1;
        return (int) Math.round(Double.parseDouble(t.endsWith(".") ? t + "0" : t) * 100);
    }

    private static String plain(int cents) {
        return String.format(Locale.GERMANY, "%.2f", cents / 100.0);
    }

    private EditText amountField() {
        EditText e = new EditText(context);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // The number keyboard offers only a point on some phones; a comma is read the same way.
        e.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
        e.setSingleLine(true);
        return e;
    }

    private View back() {
        TextView back = text(R.string.g_back, 14, R.color.text_dim);
        MainActivity.speakPlain(back);
        back.setPadding(0, dp(14), 0, dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMain();
            }
        });
        return back;
    }

    private LinearLayout card(int icon, int label, int detail) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.btn_bg);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setLayoutParams(full(6));
        ImageView pic = new ImageView(context);
        pic.setImageResource(icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ip.setMarginEnd(dp(12));
        row.addView(pic, ip);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label, 15, R.color.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(t);
        words.addView(text(detail, 12, R.color.text_dim));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private Button button(int background) {
        Button b = new Button(context);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(context.getColor(R.color.text));
        b.setBackgroundResource(background);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    private RadioButton radio(String label) {
        RadioButton b = new RadioButton(context);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setTextColor(context.getColor(R.color.text));
        return b;
    }

    private TextView heading(int res) {
        TextView t = text(res, 14, R.color.accent);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private TextView title(int res) {
        TextView t = text(res, 17, R.color.text);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView small(int res) {
        TextView t = text(res, 13, R.color.text);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView dim(int res) {
        return text(res, 13, R.color.text_dim);
    }

    private TextView text(int res, int sp, int color) {
        TextView t = new TextView(context);
        t.setText(res);
        t.setTextSize(sp);
        t.setTextColor(context.getColor(color));
        return t;
    }

    private LinearLayout.LayoutParams full(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}

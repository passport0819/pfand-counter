package app.pfandcounter;

import android.content.res.Resources;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a barcode is worth.
 *
 * A barcode does not carry the deposit — German deposit law marks single-use containers with a
 * separate DPG security label, not with anything inside the EAN. So the app keeps a list:
 * a small seeded part it ships with, and everything the user teaches it.
 */
class DepositRules {
    static final int NONE = 0;
    static final int REUSABLE_SMALL = 8;   // beer-style reusable bottle up to 0.5 l
    static final int REUSABLE = 15;        // most other reusable bottles
    static final int SINGLE_USE = 25;      // single-use PET bottle or can
    /** The usual crate deposit, "in der Regel 1,50 €" (Wikipedia „Getränkekiste“, read 28.09.2026). */
    static final int CRATE = 150;
    /** One half of a crate that splits in two: half the deposit (same source). */
    static final int HALF_CRATE = 75;

    static class Rule {
        final int cents;
        final String name;
        final boolean learned;
        /** A barcode the user taught as a crate's own label, counted apart from bottles. */
        final boolean crate;

        Rule(int cents, String name, boolean learned) {
            this(cents, name, learned, false);
        }

        Rule(int cents, String name, boolean learned, boolean crate) {
            this.cents = cents;
            this.name = name;
            this.learned = learned;
            this.crate = crate;
        }
    }

    private final Store store;
    private final Resources resources;
    private final Map<String, Rule> learned = new LinkedHashMap<String, Rule>();
    private Map<String, Rule> shipped;
    // Barcodes the list marks "?": its sources contradict each other, so the user decides —
    // no extra source may answer for them either (see ExtraSource).
    private final Set<String> askAlways = Collections.synchronizedSet(new HashSet<String>());

    DepositRules(Store store, Resources resources) {
        this.store = store;
        this.resources = resources;
        load();
        // Reading the shipped list costs a few tens of milliseconds; do it off the main thread
        // so the camera comes up first. A scan arriving earlier simply loads it on the spot.
        new Thread(new Runnable() {
            @Override public void run() {
                shippedList();
            }
        }, "deposit-list").start();
    }

    /**
     * The list the app ships with: drink cans (single-use, 0.25 by law) and German glass beer
     * bottles (0.08 by the pooled-bottle custom). Built by tools/seed-build.py from Open Food
     * Facts; the list's header names its sources.
     */
    private synchronized Map<String, Rule> shippedList() {
        if (shipped != null) return shipped;
        Map<String, Rule> list = new HashMap<String, Rule>();
        InputStream in = null;
        try {
            in = resources.openRawResource(R.raw.deposits);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8), 32768);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int first = line.indexOf('\t');
                if (first < 0) continue;
                int second = line.indexOf('\t', first + 1);
                String code = line.substring(0, first);
                String cents = second < 0 ? line.substring(first + 1) : line.substring(first + 1, second);
                String name = second < 0 ? "" : line.substring(second + 1);
                if (cents.trim().equals("?")) {
                    askAlways.add(code);
                    continue;
                }
                try {
                    list.put(code, new Rule(Integer.parseInt(cents.trim()), name, false));
                } catch (NumberFormatException ignored) {
                    // a damaged line must not cost the whole list
                }
            }
        } catch (Exception e) {
            Log.w(Store.TAG, "shipped deposit list unreadable", e);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        Log.i(Store.TAG, "shipped deposit list: " + list.size() + " barcodes");
        shipped = list;
        return shipped;
    }

    private void load() {
        String raw = store.read("rules.json");
        if (raw == null) return;
        try {
            JSONObject o = new JSONObject(raw);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String code = it.next();
                JSONObject e = o.getJSONObject(code);
                // The same article saved under both forms (before keys were matched): the later
                // entry wins, under its own key. The file changes only with the next save.
                String other = otherForm(code);
                if (other != null) learned.remove(other);
                learned.put(code, new Rule(e.getInt("c"), e.optString("n", ""), true,
                        "crate".equals(e.optString("k"))));
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "rules.json unreadable, starting empty", e);
        }
    }

    private void save() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Rule> e : learned.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("c", e.getValue().cents);
                v.put("n", e.getValue().name == null ? "" : e.getValue().name);
                if (e.getValue().crate) v.put("k", "crate");
                o.put(e.getKey(), v);
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "cannot build rules.json", e);
            return;
        }
        store.write("rules.json", o.toString());
    }

    /**
     * The same article written the other way, or null: a 12-digit UPC-A and the 13-digit GS1
     * form with a leading 0 (as a GS1 QR code carries it) name one product.
     */
    static String otherForm(String code) {
        if (code == null) return null;
        if (code.length() == 12) return "0" + code;
        if (code.length() == 13 && code.charAt(0) == '0') return code.substring(1);
        return null;
    }

    /** The key `code` is learned under (itself or its other form), or null. */
    private String learnedKey(String code) {
        if (learned.containsKey(code)) return code;
        String other = otherForm(code);
        return other != null && learned.containsKey(other) ? other : null;
    }

    /** Null means: we do not know this barcode and have to ask. */
    synchronized Rule lookup(String code) {
        String key = learnedKey(code);        // what the user taught always wins
        if (key != null) return learned.get(key);
        // Every other bottle is looked up the same way, in one list: one place to look and
        // nothing to keep in step.
        Rule r = shippedList().get(code);
        String other = otherForm(code);
        if (r == null && other != null) r = shippedList().get(other);
        return r;
    }

    /** True when the shipped list says this barcode must always be asked about. */
    boolean mustAsk(String code) {
        shippedList();
        String other = otherForm(code);
        return askAlways.contains(code) || (other != null && askAlways.contains(other));
    }

    private Map<String, int[]> makerIndex;

    /**
     * What other barcodes of the same maker in the shipped list suggest for an unknown one
     * (MakerHint): {cents, siblings}, or null. Learned amounts do not count — they are one
     * person's taps, not the measured list.
     */
    synchronized int[] makerHint(String code, ProductLookup.Info info) {
        if (info == null || info.source != ProductLookup.Source.FOOD) return null;
        if (makerIndex == null) {
            Map<String, Integer> cents = new HashMap<String, Integer>();
            for (Map.Entry<String, Rule> e : shippedList().entrySet()) cents.put(e.getKey(), e.getValue().cents);
            makerIndex = MakerHint.index(cents);
        }
        return MakerHint.hint(code, makerIndex, info.productName, info.quantity, info.categories,
                info.packaging, info.labels);
    }

    int shippedCount() {
        return shippedList().size();
    }

    synchronized int learnedCount() {
        return learned.size();
    }

    void learn(String code, int cents, String name) {
        learn(code, cents, name, false);
    }

    /** Kept under the key the article is already learned under, so it never shows twice. */
    synchronized void learn(String code, int cents, String name, boolean crate) {
        String key = learnedKey(code);
        learned.put(key != null ? key : code, new Rule(cents, name, true, crate));
        save();
    }

    /** Only drops what the user taught; a seeded rule stays. */
    synchronized boolean forget(String code) {
        boolean had = learned.remove(code) != null;
        String other = otherForm(code);
        if (other != null && learned.remove(other) != null) had = true;
        if (had) save();
        return had;
    }

    synchronized boolean isLearned(String code) {
        return learnedKey(code) != null;
    }

    /** What the user taught, newest last; a copy, so the caller may show it at leisure. */
    synchronized Map<String, Rule> learnedList() {
        return new LinkedHashMap<String, Rule>(learned);
    }

    /** "Forget all learned": the shipped list stays. */
    synchronized void forgetAll() {
        learned.clear();
        save();
    }

    /**
     * The one known barcode (learned or shipped) that differs from `code` in exactly two digits,
     * or null when there is none or more than one. A misread the check digit lets through always
     * changes at least two digits, so this is the likeliest "meant" code. Siblings of the same
     * maker differ the same way, hence only a suggestion. Measured 29.09.2026 on the shipped
     * list, each code against the others: 2 115 of 9 868 have exactly one such neighbour.
     */
    synchronized String similar(String code) {
        String found = null;
        for (Map<String, Rule> list : new Map[]{learned, shippedList()}) {
            for (String other : list.keySet()) {
                if (other.length() != code.length() || other.equals(code) || other.equals(found)) continue;
                int diff = 0;
                for (int i = 0; i < code.length() && diff <= 2; i++) {
                    if (code.charAt(i) != other.charAt(i)) diff++;
                }
                if (diff != 2) continue;
                if (found != null) return null;
                found = other;
            }
        }
        return found;
    }
}

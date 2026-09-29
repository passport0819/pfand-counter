package app.pfandcounter;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lifetime numbers for the statistics page. They are counted at the moment something is counted,
 * not taken from the list: a reset clears the list but not these, and "− / remove / change deposit"
 * take back what they undo. Numbers exist only from the version that brought this page on; nothing
 * earlier was kept. Only counts and amounts — no barcodes, no names, no places.
 */
class Stats {
    static final String FILE = "stats.json";

    /** When counting started (or was last reset), in milliseconds. */
    long since;
    int scanned;
    int byHand;
    /** Bottles per deposit amount, and crates per crate amount. */
    final TreeMap<Integer, Integer> bottles = new TreeMap<Integer, Integer>();
    final TreeMap<Integer, Integer> crates = new TreeMap<Integer, Integer>();
    /** Amount counted per month, "2026-09" → cents. */
    final TreeMap<String, Integer> months = new TreeMap<String, Integer>();

    /**
     * `qty` bottles or crates at `cents` each; negative to take back. A barcode read by the camera
     * counts as scanned, everything from a button as by hand.
     */
    void count(boolean fromCamera, boolean crate, int cents, int qty) {
        if (qty == 0) return;
        if (since == 0) since = System.currentTimeMillis();
        if (!crate) {
            if (fromCamera) scanned = Math.max(0, scanned + qty);
            else byHand = Math.max(0, byHand + qty);
        }
        bump(crate ? crates : bottles, cents, qty);
        String month = month(System.currentTimeMillis());
        Integer was = months.get(month);
        months.put(month, Math.max(0, (was == null ? 0 : was) + cents * qty));
    }

    private static void bump(TreeMap<Integer, Integer> map, int key, int qty) {
        Integer was = map.get(key);
        int now = (was == null ? 0 : was) + qty;
        if (now > 0) map.put(key, now); else map.remove(key);
    }

    static String month(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(java.util.Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    int bottleCount() {
        int n = 0;
        for (int q : bottles.values()) n += q;
        return n;
    }

    int crateCount() {
        int n = 0;
        for (int q : crates.values()) n += q;
        return n;
    }

    long totalCents() {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : bottles.entrySet()) sum += (long) e.getKey() * e.getValue();
        for (Map.Entry<Integer, Integer> e : crates.entrySet()) sum += (long) e.getKey() * e.getValue();
        return sum;
    }

    boolean isEmpty() {
        return bottles.isEmpty() && crates.isEmpty();
    }

    void clear() {
        since = System.currentTimeMillis();
        scanned = 0;
        byHand = 0;
        bottles.clear();
        crates.clear();
        months.clear();
    }

    void save(Store store) {
        try {
            JSONObject o = new JSONObject();
            o.put("since", since);
            o.put("scanned", scanned);
            o.put("hand", byHand);
            o.put("bottles", toJson(bottles));
            o.put("crates", toJson(crates));
            JSONObject m = new JSONObject();
            for (Map.Entry<String, Integer> e : months.entrySet()) m.put(e.getKey(), e.getValue());
            o.put("months", m);
            store.write(FILE, o.toString());
        } catch (JSONException e) {
            Log.w(Store.TAG, "cannot build stats.json", e);
        }
    }

    static Stats load(Store store) {
        Stats s = new Stats();
        String raw = store.read(FILE);
        if (raw == null) return s;
        try {
            JSONObject o = new JSONObject(raw);
            s.since = o.optLong("since");
            s.scanned = o.optInt("scanned");
            s.byHand = o.optInt("hand");
            fromJson(o.optJSONObject("bottles"), s.bottles);
            fromJson(o.optJSONObject("crates"), s.crates);
            JSONObject m = o.optJSONObject("months");
            if (m != null) {
                for (Iterator<String> it = m.keys(); it.hasNext(); ) {
                    String k = it.next();
                    s.months.put(k, m.getInt(k));
                }
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "stats.json unreadable, starting empty", e);
        }
        return s;
    }

    private static JSONObject toJson(TreeMap<Integer, Integer> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
        return o;
    }

    private static void fromJson(JSONObject o, TreeMap<Integer, Integer> into) throws JSONException {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                into.put(Integer.parseInt(k), o.getInt(k));
            } catch (NumberFormatException ignored) {
                // a damaged entry must not cost the rest
            }
        }
    }
}

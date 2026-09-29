package app.pfandcounter;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One pile of empties: the lines, their quantities, and the sum — in whole cents. A line is either
 * bottles or crates; crates are counted and listed apart, since a crate is no bottle.
 */
class CountSession {

    static class Line {
        final String code;
        String name;
        int cents;
        int qty;
        boolean crate;

        Line(String code, String name, int cents, int qty, boolean crate) {
            this.code = code;
            this.name = name;
            this.cents = cents;
            this.qty = qty;
            this.crate = crate;
        }

        int value() {
            return cents * qty;
        }
    }

    private final List<Line> lines = new ArrayList<Line>();

    List<Line> lines() {
        return lines;
    }

    /** Newest line first, so a fresh scan is visible without scrolling. */
    Line add(String code, String name, int cents) {
        return add(code, name, cents, 1, false);
    }

    /** `qty` of one kind at once — a full crate brings its bottles in one tap. */
    Line add(String code, String name, int cents, int qty, boolean crate) {
        for (Line l : lines) {
            if (l.code.equals(code) && l.cents == cents && l.crate == crate) {
                l.qty += qty;
                if (isBetterName(name, l.name)) l.name = name;
                lines.remove(l);
                lines.add(0, l);
                return l;
            }
        }
        Line l = new Line(code, name, cents, qty, crate);
        lines.add(0, l);
        return l;
    }

    private static boolean isBetterName(String candidate, String current) {
        return candidate != null && !candidate.isEmpty() && !candidate.equals(current);
    }

    /** A name arriving late from the online lookup. */
    boolean renameCode(String code, String name) {
        boolean touched = false;
        for (Line l : lines) {
            if (l.code.equals(code) && isBetterName(name, l.name)) {
                l.name = name;
                touched = true;
            }
        }
        return touched;
    }

    void setDeposit(Line line, int cents, boolean crate) {
        line.cents = cents;
        line.crate = crate;
        mergeDuplicates();
    }

    void remove(Line line) {
        lines.remove(line);
    }

    /** Changing a deposit value can make two lines identical; fold them back together. */
    private void mergeDuplicates() {
        for (int i = 0; i < lines.size(); i++) {
            for (int j = lines.size() - 1; j > i; j--) {
                Line a = lines.get(i), b = lines.get(j);
                if (a.code.equals(b.code) && a.cents == b.cents && a.crate == b.crate) {
                    a.qty += b.qty;
                    lines.remove(j);
                }
            }
        }
    }

    int totalCents() {
        int sum = 0;
        for (Line l : lines) sum += l.value();
        return sum;
    }

    /**
     * "3 × 0,25  ·  2 × 0,15  ·  4 × 0,08": how many bottles at each deposit, highest first, so
     * the list can be checked line by line against the machine's receipt. Crates follow apart,
     * each amount once, as "2 × 1,50 <crateWord>". Empty list → "".
     */
    String breakdown(String crateWord) {
        StringBuilder text = new StringBuilder();
        append(text, perValue(false), "");
        append(text, perValue(true), " " + crateWord);
        return text.toString();
    }

    private Map<Integer, Integer> perValue(boolean crates) {
        Map<Integer, Integer> perValue = new TreeMap<Integer, Integer>(Collections.<Integer>reverseOrder());
        for (Line l : lines) {
            if (l.crate != crates) continue;
            Integer n = perValue.get(l.cents);
            perValue.put(l.cents, (n == null ? 0 : n) + l.qty);
        }
        return perValue;
    }

    private static void append(StringBuilder text, Map<Integer, Integer> perValue, String suffix) {
        for (Map.Entry<Integer, Integer> e : perValue.entrySet()) {
            if (text.length() > 0) text.append("  ·  ");
            text.append(e.getValue()).append(" × ")
                    .append(String.format(Locale.GERMANY, "%.2f", e.getKey() / 100.0)).append(suffix);
        }
    }

    /** Bottles only; crates are counted by crateCount(). */
    int itemCount() {
        int n = 0;
        for (Line l : lines) if (!l.crate) n += l.qty;
        return n;
    }

    int crateCount() {
        int n = 0;
        for (Line l : lines) if (l.crate) n += l.qty;
        return n;
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    void clear() {
        lines.clear();
    }

    JSONArray toJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Line l : lines) {
                JSONObject o = new JSONObject();
                o.put("code", l.code);
                o.put("name", l.name == null ? "" : l.name);
                o.put("c", l.cents);
                o.put("q", l.qty);
                if (l.crate) o.put("k", "crate");
                arr.put(o);
            }
        } catch (JSONException e) {
            Log.w(Store.TAG, "cannot serialise session", e);
        }
        return arr;
    }

    static CountSession fromJson(JSONArray arr) {
        CountSession s = new CountSession();
        if (arr == null) return s;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String code = o.optString("code");
            // Lists saved before crates were marked: the crate button's line was called "crate".
            boolean crate = "crate".equals(o.optString("k")) || code.equals("crate");
            s.lines.add(new Line(code, o.optString("name"),
                    o.optInt("c"), Math.max(1, o.optInt("q", 1)), crate));
        }
        return s;
    }

    void save(Store store) {
        store.write("current.json", toJson().toString());
    }

    static CountSession load(Store store) {
        String raw = store.read("current.json");
        if (raw == null) return new CountSession();
        try {
            return fromJson(new JSONArray(raw));
        } catch (JSONException e) {
            Log.w(Store.TAG, "current.json unreadable, starting empty", e);
            return new CountSession();
        }
    }
}

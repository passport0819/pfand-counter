package app.pfandcounter;
import java.util.*;
/**
 * Checks CountSession.breakdown() on the laptop over random lists: the parts add up to the total
 * and the item count, highest deposit first, each value once, empty list → "". Crates come after
 * the bottles, marked with the crate word, and are counted apart (crateCount).
 * Expected: "Aufteilung: 10000 Listen geprüft, 0 Fehler".
 */
public class BreakdownTest {
    public static void main(String[] a) {
        Random rnd = new Random(1);
        int[] values = {8, 15, 25, 8, 15, 25, 50, 150, 3};
        int errors = 0, n = 10000;
        if (!new CountSession().breakdown("(crate)").isEmpty()) errors++;
        for (int t = 0; t < n; t++) {
            CountSession s = new CountSession();
            int adds = 1 + rnd.nextInt(40);
            for (int i = 0; i < adds; i++) {
                boolean crate = rnd.nextInt(6) == 0;
                s.add(crate ? "crate" : "code" + rnd.nextInt(12), "x", values[rnd.nextInt(values.length)],
                        1 + rnd.nextInt(3), crate);
            }
            String b = s.breakdown("(crate)");
            int sum = 0, items = 0, crates = 0, last = Integer.MAX_VALUE;
            boolean inCrates = false;
            Set<Integer> seen = new HashSet<>();
            for (String part : b.split("  ·  ")) {
                boolean crate = part.endsWith(" (crate)");
                if (crate && !inCrates) { inCrates = true; last = Integer.MAX_VALUE; seen.clear(); }
                if (!crate && inCrates) errors++; // a bottle part after the crates
                String[] p = part.replace(" (crate)", "").split(" × ");
                int qty = Integer.parseInt(p[0]);
                int cents = Math.round(Float.parseFloat(p[1].replace(',', '.')) * 100);
                if (cents >= last || !seen.add(cents)) errors++;
                last = cents;
                sum += qty * cents;
                if (crate) crates += qty; else items += qty;
            }
            if (sum != s.totalCents() || items != s.itemCount() || crates != s.crateCount()) {
                if (errors++ < 5) System.out.println("falsch: " + b + " / " + s.totalCents());
            }
        }
        System.out.println("Aufteilung: " + n + " Listen geprüft, " + errors + " Fehler");
        CountSession demo = new CountSession();
        for (int i = 0; i < 3; i++) demo.add("a", "x", 25);
        demo.add("b", "x", 15); demo.add("c", "x", 15);
        for (int i = 0; i < 4; i++) demo.add("d", "x", 8);
        demo.add("crate", "Kasten", 150, 2, true);
        demo.add("pack:beer20", "Bier", 8, 20, false);
        System.out.println("Beispiel: " + demo.breakdown("(Kasten)") + "   Flaschen " + demo.itemCount()
                + ", Kästen " + demo.crateCount());
    }
}

package app.pfandcounter;
/**
 * Laptop checks for the crate window and the statistics: typed amounts (CrateSheet.cents) and
 * the counting in Stats, including taking back. Expected: "Kasten und Statistik: 0 Fehler".
 */
public class CrateTest {
    static int errors = 0;

    static void same(String what, long got, long want) {
        if (got != want) { errors++; System.out.println("falsch: " + what + " = " + got + ", erwartet " + want); }
    }

    public static void main(String[] a) {
        same("1,50", CrateSheet.cents("1,50"), 150);
        same("1.5", CrateSheet.cents("1.5"), 150);
        same("3", CrateSheet.cents("3"), 300);
        same("0,75 €", CrateSheet.cents("0,75 €"), 75);
        same("2,", CrateSheet.cents("2,"), 200);
        same("0", CrateSheet.cents("0"), 0);
        same("leer", CrateSheet.cents(""), -1);
        same("1,505", CrateSheet.cents("1,505"), -1);
        same("abc", CrateSheet.cents("abc"), -1);
        same("-1", CrateSheet.cents("-1"), -1);
        same("1234", CrateSheet.cents("1234"), -1);

        Stats s = new Stats();
        s.count(true, false, 25, 3);       // three cans scanned
        s.count(false, false, 8, 20);      // a full beer crate by hand: bottles …
        s.count(false, true, 150, 1);      // … and the crate
        s.count(true, false, 25, -1);      // one "−"
        same("gescannt", s.scanned, 2);
        same("von Hand", s.byHand, 20);
        same("Flaschen", s.bottleCount(), 22);
        same("Kästen", s.crateCount(), 1);
        same("Summe", s.totalCents(), 2 * 25 + 20 * 8 + 150);
        same("Monat", s.months.get(Stats.month(System.currentTimeMillis())), 2 * 25 + 20 * 8 + 150);
        s.count(false, true, 150, -5);     // more taken back than counted: never below zero
        same("Kästen nie negativ", s.crateCount(), 0);
        same("kein 1,50-Eintrag", s.crates.containsKey(150) ? 1 : 0, 0);
        s.clear();
        same("zurückgesetzt", s.totalCents() + s.scanned + s.byHand + s.months.size(), 0);
        System.out.println("Kasten und Statistik: " + errors + " Fehler");
    }
}

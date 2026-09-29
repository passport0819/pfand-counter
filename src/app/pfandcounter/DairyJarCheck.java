package app.pfandcounter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Yogurt, cream or milk in a reusable deposit glass: 0.15 euro (a reusable jar many dairies
 * share). Such jars are not drinks, so neither the shipped
 * list nor Jev knew them — Jev even answered "no deposit" with high confidence (27.09.2026).
 *
 * A jar counts only when Open Food Facts says all three: a dairy category, glass, and a reusable
 * or deposit word. Glass alone is not enough (some yogurts come in single-use glass),
 * and a plastic cup never qualifies. Word for word the same rule as tools/dairy_jar_rule.py;
 * tools/DairyJarTest.java checks they agree, tools/dairy-jar-measure.py has the numbers.
 */
final class DairyJarCheck {
    private DairyJarCheck() {
    }

    private static final Set<String> DAIRY = new HashSet<String>(Arrays.asList(
            "yogurt", "yogurts", "yoghurt", "yoghurts", "joghurt", "joghurts", "dairies", "dairy",
            "quark", "quarks", "skyr", "cream", "creams", "sahne", "milk", "milks", "milch", "kefir",
            "kefirs", "buttermilk", "buttermilks"));
    /** A dairy word inside one of these is someone else's ingredient ("milk-chocolates"). */
    private static final Set<String> NOT_DAIRY = new HashSet<String>(Arrays.asList(
            "chocolate", "chocolates", "spread", "spreads", "sauce", "sauces", "dressing",
            "dressings", "cheese", "cheeses", "ice", "ices", "coffee", "coffees"));
    private static final Pattern GLASS = Pattern.compile("(^|[^a-z])(glass|glas|verre|vetro)");
    private static final Pattern REUSABLE =
            Pattern.compile("mehrweg|pfand|returnable|reusable|leihglas|(^|[^a-z])mwg([^a-z]|$)");
    private static final Pattern SINGLE_USE = Pattern.compile("einweg|non-returnable|non-reusable|"
            + "single-use|pfandfrei|kein(-| )pfand|ohne(-| )pfand|no-deposit");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^a-zäöüß]+");

    static boolean isDairy(List<String> categories) {
        boolean dairy = false;
        for (String tag : categories) {
            String rest = tag.toLowerCase(Locale.ROOT);
            int colon = rest.indexOf(':');
            if (colon >= 0) rest = rest.substring(colon + 1);
            boolean other = false, found = false;
            for (String w : NOT_A_LETTER.split(rest)) {
                if (w.isEmpty()) continue;
                if (NOT_DAIRY.contains(w)) other = true;
                if (DAIRY.contains(w)) found = true;
            }
            if (!other && found) dairy = true;
        }
        return dairy;
    }

    /** name may be null; categories, packaging and labels are Open Food Facts tags. */
    static boolean isDepositJar(String name, List<String> categories, List<String> packaging,
                                List<String> labels) {
        if (!isDairy(categories)) return false;
        String pack = join(packaging);
        if (!GLASS.matcher(pack).find()) return false;
        String hints = pack + " " + join(labels) + " " + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (SINGLE_USE.matcher(hints).find()) return false;
        return REUSABLE.matcher(hints).find();
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(' ');
            b.append(p.toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }
}

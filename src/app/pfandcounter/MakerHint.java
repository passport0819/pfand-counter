package app.pfandcounter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An unknown barcode whose manufacturer number (GS1 company prefix) it shares with at least
 * MIN_SIBLINGS barcodes of the shipped list, all with the same deposit, gets that deposit suggested:
 * a ring on the button, never counted on its own. Word for word the same rule as
 * tools/maker_rule.py; tools/MakerTest.java checks they agree, tools/maker-measure.py has the numbers.
 *
 * The list only holds deposit products, so a maker's siblings say nothing about what it sells
 * without deposit (a dairy's plastic cups, a juicer's cartons, a winery's glass bottles, a coffee
 * roaster's beans). Measured 27.09.2026 without the checks below: a third of the plastic yogurt cups
 * and a sixth of the deposit-free drinks got a wrong amount, tea bags and tinned fruit a suggestion.
 * So a hint needs the categories to say "drink" (or a dairy product in glass), and anything that
 * marks a deposit-free kind blocks it. With them, at least 9 of 10 suggestions were right, and none
 * of 266 products without deposit got one.
 */
final class MakerHint {
    private MakerHint() {
    }

    static final int[] PREFIX_LENGTHS = {9, 8, 7};
    static final int MIN_SIBLINGS = 3;
    /**
     * A dairy product in glass needs only two: of 47 makers whose glass dairy products Open Food Facts
     * calls reusable, none also sells one it calls single-use (27.09.2026).
     */
    static final int DAIRY_MIN_SIBLINGS = 2;

    private static final Pattern CARTON = Pattern.compile("brick|tetra|pure[- ]?pak|combibloc|elopak|"
            + "getränkekarton|getrankekarton|getraenkekarton|composite|verbund");
    private static final Pattern POUCH = Pattern.compile(
            "pouch|beutel|doypack|sachet|capsule|kapsel|tea-bag|(^|[^a-z])bag([^a-z]|$)");
    private static final Pattern PAPER = Pattern.compile(
            "paperboard|cardboard|(^|[^a-z])paper|karton|pappe|(^|[^a-z])film([^a-z]|$)|folie");
    private static final Pattern CONTAINER = Pattern.compile("bottle|flasche|(^|[^a-z])(can|cans|dose)([^a-z]|$)"
            + "|glas|(^|[^a-z])pet([^a-z]|$)|polyethylene-terephthalate|aluminium|metal");
    private static final Pattern GLASS = Pattern.compile("(^|[^a-z])(glass|glas)");
    private static final Pattern CAN = Pattern.compile("(^|[^a-z])(can|cans|dose|aluminium|aluminum)([^a-z]|$)");
    private static final Pattern NOT_CAN_ONLY = Pattern.compile(
            "(^|[^a-z])(glass|glas|plastic|kunststoff|pet|hdpe)([^a-z]|$)|polyethylene");
    /**
     * Stand-up pouches carry no deposit (§ 46 VerpackDG: "Folien-Standbodenbeutel"), but Open Food Facts
     * often calls them just "plastic" or says nothing. Their own signs: the word, a straw with no bottle,
     * can or cup, film of plain polyethylene (a bottle is PET or HDPE), the ten-pack of 0.2 l.
     */
    private static final Pattern POUCH_WORDS = Pattern.compile("trinkp[äa]c?k|trinkpa(ck|ket)|saftp[äa]c?k|"
            + "trinkt[üu]te|standbodenbeutel|stand-?up-?pouch");
    private static final Pattern STRAW = Pattern.compile("straw|strohhalm|trinkhalm");
    private static final Pattern HARD = Pattern.compile(
            "bottle|flasche|(^|[^a-z])(can|cans|dose|pot|cup|becher)([^a-z]|$)|glas|aluminium|metal");
    private static final Pattern PE_FILM = Pattern.compile(
            "(^|[^a-z])(ldpe|pe-ld|lldpe|pe)([^a-z]|$)|pe-7-polyethylene|low-density-polyethylene");
    private static final Pattern NOT_PE_FILM = Pattern.compile("pet|terephthalate|hdpe|high-density|"
            + "(^|[^a-z])pp([^a-z]|$)|polypropylene|bottle|flasche|glas|(^|[^a-z])(can|dose)([^a-z]|$)|metal|alumin|"
            + "(^|[^a-z])(pot|cup)([^a-z]|$)");
    private static final Pattern TEN_SMALL = Pattern.compile("(^|[^0-9])10\\s*[x×]\\s*(200\\s*ml|0[.]2\\s*l|20\\s*cl)");
    /**
     * Checks on the amount, measured on the public list (27.09.2026, tools/maker-measure.py): 0.15 on a
     * plastic bottle was right only 68 % of the time (single-use PET of a maker whose listed siblings are
     * reusable glass), so it needs a reusable word. 0.25 on glass with only a plastic or metal closure,
     * 0.08 on plastic or a can, 0.08 above 0.5 l and 0.15 above 1 l were never right.
     */
    private static final Pattern PLASTIC = Pattern.compile(
            "plastic|kunststoff|plastik|(^|[^a-z])(pet|hdpe|pp|pe)([^a-z0-9]|$)|polyethylene|polypropylene");
    private static final Pattern PET_BOTTLE = Pattern.compile(
            "(^|[^a-z])(pet|rpet)([^a-z]|$)|terephthalate|plastikflasche|plastic-bottle|pet-flasche");
    private static final Pattern REUSABLE = Pattern.compile(
            "mehrweg|reusable|re-usable|returnable|refillable|wiederverwendbar");
    private static final Pattern CAN_OR_PLASTIC = Pattern.compile(
            "(^|[^a-z])(can|cans|dose|aluminium|aluminum|plastic|kunststoff|pet)([^a-z]|$)");
    private static final Pattern SINGLE_USE = Pattern.compile("einweg|non-returnable|non-reusable|"
            + "single-use|pfandfrei|kein(-| )pfand|ohne(-| )pfand|no-deposit");
    private static final Set<String> WINE_OR_SPIRIT_TAGS = new HashSet<String>(Arrays.asList(
            "en:wines", "en:spirits", "en:liqueurs", "en:distilled-beverages", "en:sparkling-wines",
            "en:fortified-wines", "en:aperitif"));
    private static final String[] WINE_OR_SPIRIT_ENDINGS = {"-wines", "-wine", "-spirits", "-liqueurs",
            "-liqueur", "-whiskies", "-whiskeys", "-vodkas", "-gins", "-rums", "-brandies"};
    /**
     * Deposit covers 0.1 to 3 litres only (§ 46 VerpackDG, as in tools/seed-build.py). The unit's end
     * is spelled out instead of \\b: Android's regex rejects UNICODE_CHARACTER_CLASS (crash, 27.09.2026).
     */
    private static final Pattern VOLUME = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*(l|liter|litre|ml|cl)(?![a-zäöüß0-9_])");
    private static final Set<String> DRINK_ROOTS = new HashSet<String>(Arrays.asList(
            "beverages", "getranke", "getränke", "getraenke", "drinks", "boissons"));
    private static final Pattern UMBRELLA = Pattern.compile(
            "(foods?|lebensmittel)-(and|und)-(beverages|drinks|getranke|getränke)");
    private static final Set<String> NOT_READY_TAGS = new HashSet<String>(Arrays.asList(
            "en:hot-beverages", "en:beverage-preparations", "en:iced-teas-preparations",
            "en:instant-beverages", "en:powdered-beverages"));
    private static final Set<String> NOT_READY_WORDS = new HashSet<String>(Arrays.asList(
            "coffees", "teas", "supplements", "powders", "instant", "capsules", "beans", "ground",
            "vinegars"));

    /** Per prefix: {cents of the first sibling, siblings, 1 when all agree}. */
    static Map<String, int[]> index(Map<String, Integer> shipped) {
        Map<String, int[]> out = new HashMap<String, int[]>();
        for (Map.Entry<String, Integer> e : shipped.entrySet()) {
            String code = e.getKey();
            if (!isValidCode(code)) continue;
            int cents = e.getValue();
            for (int n : PREFIX_LENGTHS) {
                int[] entry = out.get(code.substring(0, n));
                if (entry == null) {
                    out.put(code.substring(0, n), new int[]{cents, 1, 1});
                } else {
                    entry[1]++;
                    if (entry[0] != cents) entry[2] = 0;
                }
            }
        }
        return out;
    }

    /** Only full 13-digit codes carry a manufacturer number worth comparing; 0 and 2 are US-style and in-store numbers. */
    static boolean isValidCode(String code) {
        if (code == null || code.length() != 13 || code.charAt(0) == '0' || code.charAt(0) == '2') return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') return false;
        }
        return true;
    }

    /** {cents, siblings} when the longest prefix with enough siblings is unanimous, else null. */
    static int[] siblings(String code, Map<String, int[]> index, int minimum) {
        if (!isValidCode(code)) return null;
        for (int n : PREFIX_LENGTHS) {
            int[] entry = index.get(code.substring(0, n));
            if (entry != null && entry[1] >= minimum) {
                return entry[2] == 1 ? new int[]{entry[0], entry[1]} : null;
            }
        }
        return null;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        if (parts != null) {
            for (String p : parts) {
                if (b.length() > 0) b.append(' ');
                b.append(p.toLowerCase(Locale.ROOT));
            }
        }
        return b.toString();
    }

    private static Set<String> words(String tag) {
        Set<String> out = new HashSet<String>();
        for (String w : tag.toLowerCase(Locale.ROOT).split("[:\\-_]")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Same reading as tools/seed-build.py: the first number with a litre unit, or -1. */
    static double litres(String quantity) {
        java.util.regex.Matcher m = VOLUME.matcher(quantity == null ? ""
                : quantity.toLowerCase(Locale.ROOT).replace(',', '.'));
        if (!m.find()) return -1;
        double value = Double.parseDouble(m.group(1));
        return m.group(2).equals("ml") ? value / 1000.0 : m.group(2).equals("cl") ? value / 100.0 : value;
    }

    static boolean isReadyDrink(List<String> categories) {
        for (String t : categories) {
            if (NOT_READY_TAGS.contains(t) || (t.endsWith("-preparations") && !t.startsWith("en:beverages-and"))) {
                return false;
            }
        }
        for (String t : categories) {
            String low = t.toLowerCase(Locale.ROOT);
            if (low.contains("drink") || low.contains("beverage") || low.contains("iced")) continue;
            for (String w : words(t)) {
                if (NOT_READY_WORDS.contains(w)) return false;
            }
        }
        for (String t : categories) {
            if (UMBRELLA.matcher(t.toLowerCase(Locale.ROOT)).find()) continue;
            for (String w : words(t)) {
                if (DRINK_ROOTS.contains(w)) return true;
            }
        }
        return false;
    }

    /** Why no hint may be given for this product, or null. */
    static String blocked(String name, String quantity, List<String> categories, List<String> packaging,
                          List<String> labels) {
        if (categories == null) categories = java.util.Collections.emptyList();
        double volume = litres(quantity);
        if (volume >= 0 && (volume < 0.1 || volume > 3.0)) return "outside 0.1 to 3 litres";
        String pack = join(packaging);
        String stated = pack + " " + join(labels) + " " + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (CARTON.matcher(pack).find() || POUCH.matcher(pack).find()
                || (PAPER.matcher(pack).find() && !CONTAINER.matcher(pack).find())) {
            return "carton or pouch";
        }
        String about = (name == null ? "" : name.toLowerCase(Locale.ROOT)) + " " + join(categories) + " " + pack;
        if (POUCH_WORDS.matcher(about).find() || (STRAW.matcher(pack).find() && !HARD.matcher(pack).find())
                || (PE_FILM.matcher(pack).find() && !NOT_PE_FILM.matcher(pack).find())
                || TEN_SMALL.matcher(quantity == null ? "" : quantity.toLowerCase(Locale.ROOT).replace(',', '.')).find()) {
            return "carton or pouch";
        }
        if (SyrupCheck.isSyrup(name, categories)) return "syrup";
        if (DairyJarCheck.isDairy(categories)) {
            if (!GLASS.matcher(pack).find() || SINGLE_USE.matcher(stated).find()) {
                return "dairy not in a reusable-looking glass";
            }
            return null;
        }
        if (DrinkCheck.isClearlyNotADrink(categories)) return "not a drink";
        for (String t : categories) {
            boolean wine = WINE_OR_SPIRIT_TAGS.contains(t);
            for (String end : WINE_OR_SPIRIT_ENDINGS) {
                if (t.endsWith(end)) wine = true;
            }
            if (wine && !CAN_OR_PLASTIC.matcher(pack).find()) return "wine or spirit";
        }
        if (!isReadyDrink(categories)) return "categories do not say ready drink";
        return null;
    }

    /** {cents, siblings} to suggest, or null. A dairy product only ever gets the 0.15 jar. */
    static int[] hint(String code, Map<String, int[]> index, String name, String quantity,
                      List<String> categories, List<String> packaging, List<String> labels) {
        if (blocked(name, quantity, categories, packaging, labels) != null) return null;
        boolean dairy = DairyJarCheck.isDairy(categories);
        int[] s = siblings(code, index, dairy ? DAIRY_MIN_SIBLINGS : MIN_SIBLINGS);
        if (s == null) return null;
        int cents = s[0];
        String pack = join(packaging);
        if (dairy && cents != DepositRules.REUSABLE) return null;
        boolean glass = GLASS.matcher(pack).find();
        if (cents == DepositRules.SINGLE_USE && glass && !CAN.matcher(pack).find() && !PET_BOTTLE.matcher(pack).find()) {
            return null;
        }
        if ((cents == DepositRules.REUSABLE_SMALL || cents == DepositRules.REUSABLE)
                && CAN.matcher(pack).find() && !NOT_CAN_ONLY.matcher(pack).find()) {
            return null;
        }
        if (cents == DepositRules.REUSABLE_SMALL && (PLASTIC.matcher(pack).find() || CAN.matcher(pack).find()) && !glass) {
            return null;
        }
        String stated = pack + " " + join(labels) + " " + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (cents == DepositRules.REUSABLE && PLASTIC.matcher(pack).find() && !glass
                && (!REUSABLE.matcher(stated).find() || SINGLE_USE.matcher(stated).find())) {
            return null;
        }
        double volume = litres(quantity);
        if ((cents == DepositRules.REUSABLE_SMALL && volume > 0.5) || (cents == DepositRules.REUSABLE && volume > 1.0)) {
            return null;
        }
        return s;
    }
}

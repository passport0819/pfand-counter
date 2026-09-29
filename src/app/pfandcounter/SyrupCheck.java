package app.pfandcounter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a drink syrup or concentrate from a drink. A syrup is not ready to drink, so its bottle
 * carries no deposit (§ 46 VerpackDG covers containers "filled with drinks"; Forum PET of the
 * IK e.V. lists "Sirupflaschen" among the containers without deposit).
 *
 * Why (25.09.2026): a SodaStream ginger ale concentrate is a syrup, yet Jev suggested
 * 0,25 €. Open Food Facts knows it by that name only, without a category. Jev also rated
 * an elderflower syrup 0,25 € — with "Sirup" in the name; the shipped list had it
 * too, by the rule "plastic bottle over 1 l". tools/seed-build.py now sweeps syrups out.
 *
 * The name decides on its own ("Sirup", "syrup", "SodaStream", …): none of 110 German SodaStream
 * products is a deposit drink. A syrup category alone does not
 * count when another category or the name points to a ready drink, because Open Food Facts files
 * some sodas and waters under syrups by mistake.
 *
 * Measured 25.09.2026 on 425 784 German products of Open Food Facts' export: no deposit drink of
 * the shipped list taken for a syrup; 2 287 products recognised as syrup. Word for
 * word the rule in tools/syrup_rule.py; tools/SyrupTest.java checks that both agree.
 */
final class SyrupCheck {
    private SyrupCheck() {
    }

    private static final Set<String> TAGS = new HashSet<String>(Arrays.asList(
            "en:syrups", "en:sirups", "de:sirup", "de:getränkesirup", "de:getränke-sirup",
            "fr:boissons-concentrees", "en:concentrates", "en:cordial", "en:cordials",
            "de:bierkonzentrat"));
    private static final String[] TAG_ENDINGS = {"-syrups", "-sirup", "-sirups", "-to-be-diluted"};

    private static final Set<String> VETO_TAGS = new HashSet<String>(Arrays.asList(
            "en:sodas", "en:colas", "en:carbonated-drinks", "en:waters", "en:mineral-waters",
            "en:spring-waters", "en:beers", "en:iced-teas", "en:energy-drinks"));
    private static final String[] VETO_TAG_ENDINGS = {"-sodas", "-colas", "-waters", "-beers"};
    private static final Pattern VETO_NAME = Pattern.compile("wasser|water|cola|bier|beer|schorle|radler|limo");

    private static final Pattern NAME = Pattern.compile(
            "sirup|syrup|sirop|sciroppo|getränkekonzentrat|bierkonzentrat|dicksaft|sodastream");

    static boolean isSyrup(String name, List<String> tags) {
        return reason(name, tags) != null;
    }

    /** "name: sirup", "category en:syrups" — or null when it is not a syrup. */
    static String reason(String name, List<String> tags) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        Matcher m = NAME.matcher(low);
        if (m.find()) return "name: " + m.group();
        if (tags == null) return null;
        String hit = null;
        for (String t : tags) {
            if (TAGS.contains(t) || endsWithAny(t, TAG_ENDINGS)) {
                hit = t;
                break;
            }
        }
        if (hit == null) return null;
        for (String t : tags) {
            if (VETO_TAGS.contains(t) || endsWithAny(t, VETO_TAG_ENDINGS)) return null;
        }
        if (VETO_NAME.matcher(low).find()) return null;
        return "category " + hit;
    }

    private static boolean endsWithAny(String s, String[] endings) {
        for (String e : endings) if (s.endsWith(e)) return true;
        return false;
    }
}

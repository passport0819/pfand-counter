package app.pfandcounter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tells from Open Beauty Facts or Open Products Facts whether a barcode is clearly not a drink —
 * shampoo, toothpaste, batteries, dish tabs. The app asks these two only when Open Food Facts
 * does not know the barcode at all.
 *
 * Being listed there is not enough: a few real drinks sit there misfiled (a Helles, Cola Zero,
 * mineral water, iced tea — found 25.09.2026). So a product counts as "not a drink" only when it
 * has a category AND neither its categories nor its name contain a single drink word. Cosmetic
 * words that merely look like drinks (mouthwash, face water, body milk, serum, intimate) are
 * taken out first. With a care or household category (shampoo, soap, detergent …) the name no
 * longer counts, unless it is plainly a drink ("mineral water", "lemonade", "beer").
 *
 * Measured 25.09.2026 on both exports (64 237 + 41 133 products): 0 of the 9 623 known deposit
 * drinks and none of the 13 misfiled control drinks taken for non-food; 25 010 + 21 548 products
 * recognised (before the change of 25.09.2026 evening: 23 875 + 21 032). Same rule as
 * tools/nonfood-measure.py; tools/NonFoodTest.java compares them row by row.
 * Word ends are spelled out as (?![a-z]) instead of \b, which Android's regex engine reads
 * differently from Python's.
 */
final class NonFoodCheck {
    private NonFoodCheck() {
    }

    private static final Pattern EXEMPT = Pattern.compile(
            "mundwasser|mund-wasser|gesichtswasser|rasierwasser|haarwasser|micell|mizell|cleansing-water"
                    + "|cleansing-milk|body-milk|bodymilk|körpermilch|korpermilch|körper-milch|reinigungsmilch"
                    + "|sonnenmilch|lait|mouthwash|tea-tree|teebaum|teelicht"
                    + "|serum|sérum|intimate|ultimate|climate|chocolat|escolar|ecolabel|dulcolax|virgin|origin|platte"
                    + "|waterproof|water-?resistant|wasserfest|wasserabweis|wasserminze|wasserlilie|wasserball"
                    + "|liquid-detergent|liquid detergent|liquide-vaisselle|liquide vaisselle|liquid-soap|liquid soap"
                    + "|savons?-liquides?|savon liquide|sapone-liquido|sapone liquido|jabon(es)?-liquidos?"
                    + "|dishwashing-liquid|dishwashing liquid|fond-de-teint-liquide|hydro-?alcoh?oli|rubbing-alcohol"
                    + "|rubbing alcohol|isopropyl|hair-? ?tonic|facial tonic|face tonic"
                    + "|beard tonic|tonico-facial|reinigingstonic|cocoa-butter|cocoa butter|kakaobutter|mineral-sun"
                    + "|mineral sun|mineral oil|mineral-oil|body milch|milch (&|und) honig|coconut milk shampoo"
                    + "|latte solare|latte di cocco|energy 24h|trinkhalm");

    private static final Pattern DRINK = Pattern.compile(
            "beverage|drink|getr[aä]nk|boisson|bebida|bevand|drank|water|wasser|juice|juic|saft|s[aä]fte"
                    + "|nectar|nektar|soda|limo|lemonade|bier|beer|wine|wein|cider|cidre|spirit|liq|lik[oö]r"
                    + "|alkohol|alcohol|vodka|wodka|gin(?![a-z])|rum(?![a-z])|whisk|milk|milch|kefir"
                    + "|(?<![a-z])tea(?![a-z])|(?<![a-z])tee(?![a-z])|teas(?![a-z])"
                    + "|eistee|iced|coffee|kaffee|latte|kakao|cocoa|mate(?![a-z])|cola|kola|schorle|spezi|radler|pils"
                    + "|helles|weizen|lager(?![a-z])|bräu|brauerei|fanta|sprite|pepsi|red ?bull|energy|smoothie|shake"
                    + "|kombucha|sirup|syrup|sprudel|mineral|softdrink|brause|sekt|prosecco|champagne|aperitif"
                    + "|tonic|punsch|mojito|stout|bock(?![a-z])|trink|(?<![a-z])spritz(?![a-z])|margarita|mixery");

    /**
     * Care and household categories. With one of these (and no drink word among the categories)
     * the name no longer counts: a honey-milk shower cream, a beer shampoo, a liquid detergent.
     * Generic tags like "cosmetic-products" are deliberately not here — a cola sits under it in
     * Open Beauty Facts.
     */
    private static final Pattern CARE = Pattern.compile(
            "shampoo|shower|soaps?(?![a-z])|toothpaste|mouthwash|dentifrice|zahnpasta|duschgel|douche|seife"
                    + "|savon|deodorant|sunscreen|skincare|face-|facial|hair-|body-cream|body-lotion|makeup|make-up"
                    + "|detergent|dishwash|household|laundry|lip-balm|nail|perfume|creams|toner|haushalt");

    /**
     * Names that are a drink and nothing else. They ask even under a care category: Open Beauty
     * Facts files a plain bottled mineral water under face make-up.
     */
    private static final Pattern PLAIN_DRINK = Pattern.compile(
            "mineral ?water|mineralwasser|drinking water|trinkwasser|tafelwasser|table water|sparkling water"
                    + "|still water|lemonade|limonade|softdrink|soft drink|energy ?drink|eistee|iced tea|ice tea"
                    + "|(?<![a-z])bier(?![a-z])|(?<![a-z])beer(?![a-z])|(?<![a-z])saft(?![a-z])|(?<![a-z])juice(?![a-z])"
                    + "|(?<![a-z])cola(?![a-z])|(?<![a-z])wein(?![a-z])|(?<![a-z])wine(?![a-z])");

    /** A drink word in the product name alone ("Mineralwasser", "Radler", "Mate"). */
    static boolean nameSoundsLikeDrink(String name) {
        if (name == null) return false;
        String plain = EXEMPT.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return DRINK.matcher(plain).find();
    }

    static boolean isClearlyNotADrink(String name, List<String> tags) {
        if (tags == null || tags.isEmpty()) return false;
        StringBuilder text = new StringBuilder();
        for (String tag : tags) {
            if (text.length() > 0) text.append(',');
            text.append(tag);
        }
        String cats = text.toString().toLowerCase(Locale.ROOT);
        if (DRINK.matcher(EXEMPT.matcher(cats).replaceAll(" ")).find()) return false;
        String plainName = EXEMPT.matcher(name == null ? "" : name.toLowerCase(Locale.ROOT)).replaceAll(" ");
        if (CARE.matcher(cats).find() && !PLAIN_DRINK.matcher(plainName).find()) return true;
        return !nameSoundsLikeDrink(name);
    }
}

package app.pfandcounter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells from Open Food Facts' categories whether a barcode is clearly not a drink — gummy bears,
 * tuna, crisps — so the app need not ask for a deposit on it.
 *
 * Deliberately one-sided: a product counts as "not a drink" only when one of its categories is
 * a known food group AND not a single category word hints at anything drinkable (or at a
 * dairy jar, which can carry deposit). Everything else — no categories, odd tags, anything
 * drink-like — falls back to asking, as before.
 *
 * Umbrella tags that name drinks only as the other half of a food group ("en:plant-based-foods-
 * and-beverages" sits on every crisp, tin of tomatoes and pasta) do not count as a drink hint,
 * nor does "milk" in milk chocolate, nor the dairy parents of a cheese. The first version
 * missed that and let a tube of crisps through (25.09.2026).
 *
 * Measured 25.09.2026 on Open Food Facts' export (576 424 German products): all 9 594 deposit
 * drinks of the shipped list pass as drinks (0 false alarms); 162 951 products are recognised as
 * food. Same rule as the Python measurement; tools/NotDrinkTest.java checks they agree.
 */
final class DrinkCheck {
    private DrinkCheck() {
    }

    /** Whole words inside a category tag ("en:milk-chocolates" → milk, chocolates). */
    private static final Set<String> DRINK_WORDS = new HashSet<String>(Arrays.asList(
            "beverage", "beverages", "drink", "drinks", "water", "waters", "juice", "juices",
            "nectar", "nectars", "soda", "sodas", "lemonade", "lemonades", "beer", "beers",
            "wine", "wines", "cider", "ciders", "spirit", "spirits", "liqueur", "liqueurs",
            "milk", "milks", "kefir", "kefirs", "buttermilk", "buttermilks", "ayran", "lassi",
            "smoothie", "smoothies", "tea", "teas", "coffee", "coffees", "dairies", "dairy",
            "yogurt", "yogurts", "yoghurt", "yoghurts", "skyr", "quark", "quarks", "kombucha",
            "syrup", "syrups", "shake", "shakes", "mate", "cola", "colas", "schorle", "spritzer",
            "punch", "cocktail", "cocktails", "alcoholic", "alcohol", "vinegar", "vinegars",
            "infusion", "infusions", "sake", "mead", "kvass",
            // tags Open Food Facts never translated
            "getranke", "getraenke", "getränke", "boissons", "boisson", "bebidas", "bevande",
            "dranken", "saft", "safte", "säfte", "eistee", "bier", "wasser", "limonade", "tee",
            "kaffee", "milch", "fruchsaft", "fruchtsaft", "mineralwasser", "sprudel", "brause",
            "energy"));

    /** Food groups that are never a drink container on their own. */
    private static final Set<String> FOOD_GROUPS = new HashSet<String>();

    static {
        for (String g : ("snacks sweet-snacks salty-snacks confectioneries candies chocolates "
                + "meats-and-their-products meats prepared-meats condiments sauces meals seafood "
                + "fishes-and-their-products desserts breakfasts frozen-foods meat-alternatives "
                + "sweeteners canned-foods spreads sandwiches dried-products toppings-ingredients "
                + "food-additives cooking-helpers cocoa-and-its-products broths entrees "
                + "eggs-and-their-products chips-and-fries fats crepes-and-galettes appetizers-sides "
                + "fish-and-meat-and-eggs salads bread-coverings baked-goods sweet-pies "
                + "non-food-products cheeses breads biscuits-and-cakes cereals-and-potatoes "
                + "cereals-and-their-products pastas plant-based-foods "
                + "fruits-and-vegetables-based-foods").split(" ")) {
            FOOD_GROUPS.add("en:" + g);
        }
    }

    private static final Pattern UMBRELLA = Pattern.compile(
            "(foods|food)-and-(beverages|drinks)$|^en:christmas-foods-and-drinks$"
                    + "|lebensmittel-und-getranke$|milk-chocolate");

    private static final Set<String> CHEESE_PARENTS = new HashSet<String>(Arrays.asList(
            "en:dairies", "en:fermented-foods", "en:fermented-milk-products"));

    static boolean isClearlyNotADrink(List<String> tags) {
        if (tags == null || tags.isEmpty()) return false;
        boolean food = false;
        for (String tag : tags) {
            if (FOOD_GROUPS.contains(tag)) food = true;
        }
        if (!food) return false;
        boolean cheese = tags.contains("en:cheeses");
        for (String tag : tags) {
            if (UMBRELLA.matcher(tag).find()) continue;
            if (cheese && CHEESE_PARENTS.contains(tag)) continue;
            for (String word : tag.toLowerCase(Locale.ROOT).split("[:\\-_]")) {
                if (DRINK_WORDS.contains(word)) return false;
            }
        }
        return true;
    }

    /** True when any category (umbrella tags aside) carries a drink word — the "Ask Jev" colour. */
    static boolean hasDrinkHint(List<String> tags) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (UMBRELLA.matcher(tag).find()) continue;
            for (String word : tag.toLowerCase(Locale.ROOT).split("[:\\-_]")) {
                if (DRINK_WORDS.contains(word)) return true;
            }
        }
        return false;
    }

    /** The most specific English category, readable: "en:sweet-snacks" → "Sweet snacks". */
    static String describe(List<String> tags) {
        if (tags == null) return null;
        for (int i = tags.size() - 1; i >= 0; i--) {
            String t = tags.get(i);
            if (!t.startsWith("en:") || t.length() <= 3) continue;
            String words = t.substring(3).replace('-', ' ');
            return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
        return null;
    }
}

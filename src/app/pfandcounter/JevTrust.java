package app.pfandcounter;

/**
 * How often Jev was right at a given confidence — measured, not what Jev says of itself.
 *
 * Source: data/jev-kontrollantworten-27-09.jsonl, 367 control cases with a known answer, run
 * 27.09.2026 with the same question JevClient asks (including the dairy jar choice "jar15"):
 * the 212 drinks of the 22.09. runs, 89 dairy products in a reusable deposit glass (0.15),
 * 60 in a plastic cup and 6 in a single-use jar. Dairy products get a table of
 * their own (DAIRY_BANDS): Jev called most deposit jars "no deposit" at 0.80 to 0.95, so a
 * shared table would have vouched for exactly those misses. Counted by tools/jev-trust.py,
 * which fails if this file disagrees. The samples are small — the app shows the raw counts
 * so nobody reads 21 of 25 as a law.
 */
final class JevTrust {
    /** {lower bound of confidence, right, asked}, highest band first. */
    static final double[][] BANDS = {
            {0.95, 54, 62},
            {0.90, 21, 25},
            {0.80, 16, 24},
            {0.70, 14, 20},
            {0.50, 21, 33},
            {0.00, 19, 48},
    };

    /** The same for dairy products (DairyJarCheck.isDairy on the categories), 155 cases. */
    static final double[][] DAIRY_BANDS = {
            {0.95, 80, 80},
            {0.90, 2, 14},
            {0.80, 5, 23},
            {0.70, 8, 11},
            {0.50, 10, 18},
            {0.00, 5, 9},
    };

    /**
     * The same for "is this a drink?": 350 control products, categories stripped
     * (data/jev-getraenk-antworten.jsonl and …-de.jsonl, run 25.09.2026 by tools/jev-drink-measure.py).
     */
    static final double[][] DRINK_BANDS = {
            {0.95, 333, 333},
            {0.90, 4, 4},
            {0.80, 5, 5},
            {0.70, 0, 0},
            {0.50, 1, 1},
            {0.00, 5, 7},
    };

    private JevTrust() { }

    /** The band a confidence falls into: {lower, upper, right, asked}. */
    static double[] bandOf(double confidence) {
        return bandOf(BANDS, confidence);
    }

    static double[] dairyBandOf(double confidence) {
        return bandOf(DAIRY_BANDS, confidence);
    }

    static double[] drinkBandOf(double confidence) {
        return bandOf(DRINK_BANDS, confidence);
    }

    private static double[] bandOf(double[][] bands, double confidence) {
        double upper = 1.0;
        for (double[] b : bands) {
            if (confidence >= b[0]) return new double[]{b[0], upper, b[1], b[2]};
            upper = b[0];
        }
        double[] last = bands[bands.length - 1];
        return new double[]{last[0], upper, last[1], last[2]};
    }
}

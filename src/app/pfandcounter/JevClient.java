package app.pfandcounter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asks Jev (jev-1.13 by TypeSafe) which deposit an unknown drink carries, either through
 * OpenRouter's decisions endpoint or directly at TypeSafe — whichever key the user gave. Both take
 * the same request and give the same answers; only address, model name and the cost field differ
 * (TypeSafe's API reference, api.typesafe.ai/openapi.json 0.2.0, read 27.09.2026). Jev only
 * suggests — nothing is learned until the user taps a button.
 *
 * The question is word for word the one tools/jev-classify.py asks, one item instead of forty,
 * so that the hit rates measured there (JevTrust) describe it. Extended 27.09.2026 by the dairy
 * jar choice "jar15" and measured again the same day (JevTrust).
 * Only the product text goes out: name, size, packaging words, three categories. Not the barcode.
 *
 * Each key is typed in by the user on the phone. It lives in noBackupFilesDir, so the phone's
 * backup never carries it off, and it is never shown or logged.
 */
class JevClient {
    enum Provider {
        OPENROUTER("https://openrouter.ai/api/alpha/decisions", "typesafe/jev-1.13", "openrouter.key"),
        /** The pinned version, not "jev-latest": JevTrust was measured on 1.13. */
        TYPESAFE("https://api.typesafe.ai/v1/systemone", "jev-1.13.0", "typesafe.key");

        final String url, model, keyFile;

        Provider(String url, String model, String keyFile) {
            this.url = url;
            this.model = model;
            this.keyFile = keyFile;
        }
    }

    static final String MODEL = Provider.OPENROUTER.model;
    /**
     * TypeSafe answers with token counts, not a price: $0.042 per million input tokens, output free
     * (docs.typesafe.ai/models.md, read 27.09.2026). OpenRouter sends the price itself.
     */
    static final double TYPESAFE_USD_PER_INPUT_TOKEN = 0.042 / 1000000.0;
    private static final String PROVIDER_FILE = "jev-provider.txt";
    /** "1" once the user switched Jev on in its settings; missing means off (opt-in). */
    private static final String ENABLED_FILE = "jev-enabled.txt";
    private static final String SPENT_FILE = "jev-spent.txt";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    // Measured 22.09.2026: the endpoint once took 50 s for a mere 401. Slow, not broken.
    private static final int READ_TIMEOUT_MS = 60000;

    static final String STATE_HEAD =
            "German bottle deposit (Pfand). Each item below is one drink or dairy product sold in Germany, with "
            + "its brand, name, size and whatever the packaging data says. Decide which deposit the empty "
            + "container is worth when it is returned to a shop.\n\n";

    static final String[][] CRITERIA = {
            {"c8", "Reusable bottle in the German pooled-bottle system, up to 0.5 litres — the plain "
                    + "brown or green beer bottle. Deposit 0.08 EUR."},
            {"c15", "Reusable bottle outside that system: swing-top beer, mineral water in glass or in "
                    + "reusable PET, juice or soft drinks in a reusable bottle. Deposit 0.15 EUR."},
            {"c25", "Single-use container: a can, or a bottle that is thrown away after one use — most "
                    + "PET bottles of water, soft drinks and beer sold in supermarkets. Deposit 0.25 EUR."},
            {"none", "No deposit at all in Germany: wine, sparkling wine, spirits, milk in a carton, "
                    + "juice in a single-use glass bottle or carton, containers under 0.1 or over 3 litres, "
                    + "yogurt or cream in a plastic cup or a single-use jar."},
            {"jar15", "Reusable glass jar of yogurt, cream, quark or milk, marked Mehrweg or Pfand, "
                    + "taken back by the shop like a bottle. Deposit 0.15 EUR."},
    };

    /**
     * The second question, asked in its own request so the deposit question above stays word
     * for word the measured one. Measured 25.09.2026 (tools/jev-drink-measure.py), categories
     * stripped: 100/100 drinks and 100/100 foods; German set 48/50 drinks and 100/100 cosmetics
     * and household goods. The two misses were unsure — hence the 80 % bar in notADrink().
     */
    static final String DRINK_STATE_HEAD =
            "Products sold in Germany, each with whatever its database entry says: brand, name, size, "
            + "packaging. Some entries are incomplete or wrong. Decide for each whether it is a drink.\n\n";

    static final String[][] DRINK_CRITERIA = {
            {"drink", "A drink: water, soft drink, juice, beer, wine, spirits, a milk, tea or coffee drink — "
                    + "sold ready to drink in a bottle, can or carton."},
            {"other", "Not a drink: food, sweets, snacks, cosmetics, cleaning or household goods, clothing, "
                    + "or anything else that is not drunk."},
    };

    /** The deposit button a choice belongs to: 0.08, 0.15, 0.25, none. */
    static int buttonOf(String choice) {
        return "c8".equals(choice) ? 0 : "c15".equals(choice) || "jar15".equals(choice) ? 1
                : "c25".equals(choice) ? 2 : 3;
    }

    static int centsOf(String choice) {
        if ("c8".equals(choice)) return DepositRules.REUSABLE_SMALL;
        if ("c15".equals(choice) || "jar15".equals(choice)) return DepositRules.REUSABLE;
        if ("c25".equals(choice)) return DepositRules.SINGLE_USE;
        if ("none".equals(choice)) return DepositRules.NONE;
        return -1;
    }

    /** What Jev said about one product. */
    static class Suggestion {
        String choice;
        int cents;
        double confidence;
        /** Per deposit button: 0.08, 0.15 (reusable bottle and jar together), 0.25, none; -1 where Jev gave none. */
        final double[] probabilities = {-1, -1, -1, -1};
        double costUsd;
        long millis;
        /** Jev's odds that this is a drink at all; -1 when that answer is missing. */
        double drinkProbability = -1;
        double drinkConfidence = -1;

        /**
         * Only from 80 % "not a drink": at 50 % two of 50 German drinks (a plum brandy, a juice)
         * would have been turned away; from 80 % none of 150 drinks, while 199 of 200 foods and
         * 100 of 100 cosmetics and household goods are still caught (tools/jev-drink-measure.py).
         */
        boolean notADrink() {
            return drinkProbability >= 0 && drinkProbability <= 0.2;
        }
    }

    enum Problem { NO_KEY, NO_DATA, KEY_REJECTED, NO_CREDIT, BUSY, TIMEOUT, OFFLINE, FAILED }

    /** Exactly one of the two is called, on the main thread. */
    interface Callback {
        void onSuggestion(Suggestion s);
        void onProblem(Problem p, String detail);
    }

    private final File dir;
    private final File providerFile;
    private final File spentFile;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    JevClient(Context ctx) {
        dir = ctx.getNoBackupFilesDir();
        providerFile = new File(dir, PROVIDER_FILE);
        spentFile = new File(dir, SPENT_FILE);
    }

    private File keyFile(Provider p) {
        return new File(dir, p.keyFile);
    }

    /**
     * The way Jev is asked: the one the user picked last, else the one with a key (OpenRouter first,
     * as before there was a choice).
     */
    Provider provider() {
        String chosen = readFile(providerFile);
        if (chosen != null) {
            for (Provider p : Provider.values()) {
                if (p.name().equals(chosen.trim())) return p;
            }
        }
        return !hasKey(Provider.OPENROUTER) && hasKey(Provider.TYPESAFE) ? Provider.TYPESAFE : Provider.OPENROUTER;
    }

    void setProvider(Provider p) {
        writeFile(providerFile, p.name());
    }

    /** Jev is off until switched on: no "Ask Jev" button, no key fields, nothing is ever sent. */
    boolean enabled() {
        String v = readFile(new File(dir, ENABLED_FILE));
        return v != null && v.trim().equals("1");
    }

    void setEnabled(boolean on) {
        writeFile(new File(dir, ENABLED_FILE), on ? "1" : "0");
    }

    boolean hasKey() {
        return hasKey(provider());
    }

    boolean hasKey(Provider p) {
        String k = readFile(keyFile(p));
        return k != null && !k.trim().isEmpty();
    }

    void setKey(Provider p, String key) {
        writeFile(keyFile(p), key.trim());
    }

    void removeKey(Provider p) {
        File f = keyFile(p);
        if (f.exists() && !f.delete()) Log.w(Store.TAG, "cannot delete the Jev key");
    }

    /** {questions asked, dollars spent} since the key was first set on this phone. */
    double[] spent() {
        String s = readFile(spentFile);
        if (s == null) return new double[]{0, 0};
        try {
            String[] parts = s.trim().split(" ");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    private synchronized void addSpent(double usd) {
        double[] was = spent();
        writeFile(spentFile, String.format(Locale.ROOT, "%d %.8f", (long) was[0] + 1, was[1] + usd));
    }

    void ask(final String text, final Callback callback) {
        final Provider provider = provider();
        final String key = readFile(keyFile(provider));
        if (!enabled() || key == null || key.trim().isEmpty()) {
            callback.onProblem(Problem.NO_KEY, null);
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            callback.onProblem(Problem.NO_DATA, null);
            return;
        }
        pool.execute(new Runnable() {
            @Override public void run() {
                final long began = System.currentTimeMillis();
                Suggestion s = null;
                Problem problem = null;
                String detail = null;
                Log.i(Store.TAG, "Jev asked via " + provider);
                try {
                    s = parse(post(provider, key.trim(), requestBody(text, provider.model)));
                    try {
                        JSONObject drink = drinkAnswer(post(provider, key.trim(), drinkRequestBody(text, provider.model)));
                        JSONObject p = drink.optJSONObject("probabilities");
                        if (p != null) s.drinkProbability = p.optDouble("drink", -1);
                        s.drinkConfidence = drink.optDouble("confidence", -1);
                        s.costUsd += drink.optDouble("cost", 0);
                    } catch (Exception e) {
                        Log.i(Store.TAG, "Jev drink question failed: " + e.getClass().getSimpleName());
                    }
                    s.millis = System.currentTimeMillis() - began;
                    addSpent(s.costUsd);
                } catch (HttpProblem e) {
                    problem = e.problem;
                    detail = e.getMessage();
                } catch (SocketTimeoutException e) {
                    problem = Problem.TIMEOUT;
                } catch (java.net.UnknownHostException e) {
                    problem = Problem.OFFLINE;
                } catch (Exception e) {
                    problem = Problem.FAILED;
                    detail = e.getClass().getSimpleName();
                    Log.i(Store.TAG, "Jev failed: " + e.getClass().getSimpleName());
                }
                final Suggestion done = s;
                final Problem failed = problem;
                final String why = detail;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (done != null) callback.onSuggestion(done);
                        else callback.onProblem(failed, why);
                    }
                });
            }
        });
    }

    /** The request body, as tools/jev-classify.py builds it for a batch of one. */
    static String requestBody(String text) throws Exception {
        return requestBody(text, MODEL);
    }

    static String requestBody(String text, String model) throws Exception {
        JSONObject criteria = new JSONObject();
        for (String[] c : CRITERIA) criteria.put(c[0], c[1]);
        JSONObject item = new JSONObject();
        item.put("type", "choice");
        item.put("instructions", "Item [1]: which deposit does this container carry?");
        item.put("criteria", criteria);
        JSONObject questions = new JSONObject();
        questions.put("item1", item);
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("state", STATE_HEAD + "[1] " + text);
        body.put("questions", questions);
        return body.toString();
    }

    /** "Is this a drink at all?", as tools/jev-drink-measure.py asks it for a batch of one. */
    static String drinkRequestBody(String text) throws Exception {
        return drinkRequestBody(text, MODEL);
    }

    static String drinkRequestBody(String text, String model) throws Exception {
        JSONObject criteria = new JSONObject();
        for (String[] c : DRINK_CRITERIA) criteria.put(c[0], c[1]);
        JSONObject item = new JSONObject();
        item.put("type", "choice");
        item.put("instructions", "Item [1]: is this a drink?");
        item.put("criteria", criteria);
        JSONObject questions = new JSONObject();
        questions.put("item1", item);
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("state", DRINK_STATE_HEAD + "[1] " + text);
        body.put("questions", questions);
        return body.toString();
    }

    /** The drink answer, with the request's cost copied in as "cost". */
    static JSONObject drinkAnswer(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONObject answers = root.optJSONObject("answers");
        JSONObject a = answers != null ? answers.optJSONObject("item1") : null;
        if (a == null) throw new HttpProblem(Problem.FAILED, "no drink answer");
        a.put("cost", costOf(root));
        return a;
    }

    /** Reads Jev's answer; throws when there is no usable choice in it. */
    static Suggestion parse(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONObject answers = root.optJSONObject("answers");
        JSONObject a = answers != null ? answers.optJSONObject("item1") : null;
        if (a == null) throw new HttpProblem(Problem.FAILED, "no answer");
        Suggestion s = new Suggestion();
        s.choice = a.optString("choice", null);
        s.cents = centsOf(s.choice);
        if (s.cents < 0) throw new HttpProblem(Problem.FAILED, "choice " + s.choice);
        s.confidence = a.optDouble("confidence", -1);
        JSONObject p = a.optJSONObject("probabilities");
        if (p != null) {
            for (int i = 0; i < CRITERIA.length; i++) {
                double v = p.optDouble(CRITERIA[i][0], -1);
                if (v < 0) continue;
                int button = buttonOf(CRITERIA[i][0]);
                s.probabilities[button] = Math.max(0, s.probabilities[button]) + v;
            }
        }
        s.costUsd = costOf(root);
        return s;
    }

    /** OpenRouter's "usage.cost", else TypeSafe's billable "usage.input_tokens" at the list price. */
    static double costOf(JSONObject root) {
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) return 0;
        if (usage.has("cost")) return usage.optDouble("cost", 0);
        return usage.optLong("input_tokens", 0) * TYPESAFE_USD_PER_INPUT_TOKEN;
    }

    private static String post(Provider provider, String key, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(provider.url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "application/json");
            if (provider == Provider.OPENROUTER) conn.setRequestProperty("X-Title", "Pfand Counter deposit list");
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) throw new HttpProblem(Problem.KEY_REJECTED, "HTTP " + status);
            if (status == 402) throw new HttpProblem(Problem.NO_CREDIT, "HTTP 402");
            // 529: TypeSafe's "overloaded" (its API reference names 401, 422, 429 and 529).
            if (status == 429 || status == 529) throw new HttpProblem(Problem.BUSY, "HTTP " + status);
            if (status != 200) throw new HttpProblem(Problem.FAILED, "HTTP " + status);
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static class HttpProblem extends Exception {
        final Problem problem;

        HttpProblem(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0 && buf.size() < 256 * 1024) buf.write(chunk, 0, n);
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try {
            InputStream in = new java.io.FileInputStream(f);
            return readAll(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, String content) {
        try {
            OutputStream out = new java.io.FileOutputStream(f);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (java.io.IOException e) {
            Log.w(Store.TAG, "cannot write " + f.getName());
        }
    }
}

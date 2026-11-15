package app.pfandcounter;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asks Open Food Facts what a barcode is called and which food group it belongs to. It never
 * decides a deposit; the categories only let the app skip the question for things that are
 * clearly not a drink (see DrinkCheck). Everything works without it — the network call runs on
 * its own thread with a short timeout, so no camera frame ever waits for it.
 *
 * Only when Open Food Facts answers "no such product" are its two sister databases asked in
 * turn — Open Beauty Facts (shampoo, toothpaste) and Open Products Facts (batteries, dish tabs);
 * see NonFoodCheck. Without internet nothing more is tried. Each answered in 0,29–0,41 s
 * (measured 25.09.2026), so all three together stay well inside the app's 3 s wait.
 *
 * Up to PARALLEL barcodes are looked up at once, so one hanging answer does not hold up the next
 * scan; the same barcode is never asked twice at once. Requests to the databases together start
 * at least MIN_GAP_MS apart (shared by every instance), about the fastest answer above: no
 * faster than one lookup after another with quick answers, as before.
 */
class ProductLookup {
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    /** Different barcodes looked up at the same time; more wait their turn. */
    static final int PARALLEL = 3;
    /** One lookup, all databases together, gives up after about this long (before: up to 3 × 8 s). */
    static final long TOTAL_DEADLINE_MS = 8000;
    /** Least time between two requests, to any of the three databases. */
    static final long MIN_GAP_MS = 300;
    /** Tests only: sent here instead of the databases, e.g. "http://127.0.0.1:1234". */
    static volatile String testBase;

    /** Where an answer came from, and the name the "Not a drink" notice gives it. */
    enum Source {
        FOOD("world.openfoodfacts.org", "Open Food Facts"),
        BEAUTY("world.openbeautyfacts.org", "Open Beauty Facts"),
        PRODUCTS("world.openproductsfacts.org", "Open Products Facts");

        final String host;
        final String label;

        Source(String host, String label) {
            this.host = host;
            this.label = label;
        }
    }

    /**
     * Inside fetch only: "the database answered: no such product", as opposed to no answer at
     * all. Never handed out (its categories are null); fetchAnywhere turns it into notFound().
     */
    private static final Info NOT_FOUND = new Info(null, null, Source.FOOD);

    interface Callback {
        void onName(String code, String name);
    }

    /** What one of the three databases knows about a barcode. */
    static class Info {
        final String name;
        final List<String> categories;
        final Source source;
        /** The product in one line (describe); null when there is nothing to read it from. */
        String summaryText;
        /** Packaging tags (material, shape, recycling of each part, then packaging_tags) and labels. */
        List<String> packaging = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        /** Open Food Facts' product_name alone, without brand and size — what the jar rule reads. */
        String productName = "";
        /** Open Food Facts' quantity as written ("0,5 l", "6 x 330 ml"); MakerHint reads the litres. */
        String quantity = "";

        Info(String name, List<String> categories, Source source) {
            this.name = name;
            this.categories = categories;
            this.source = source;
        }

        /** Yogurt or cream in a reusable deposit glass (DairyJarCheck). */
        boolean isDepositJar() {
            return source == Source.FOOD && DairyJarCheck.isDepositJar(productName, categories, packaging, labels);
        }

        boolean clearlyNotADrink() {
            return source == Source.FOOD ? DrinkCheck.isClearlyNotADrink(categories)
                    : NonFoodCheck.isClearlyNotADrink(name, categories);
        }
    }

    /**
     * Called exactly once per ask, on the main thread. info is null only when no answer came
     * (network error, timeout, deadline, unreadable answer); a later ask tries again. A barcode
     * that every database answered "no such product" for comes back as a not-found Info
     * (isNotFound), cached like any answer: no name, no categories, no summary line.
     */
    interface InfoCallback {
        void onInfo(String code, Info info);
    }

    /** Idle threads end after 30 s, so an activity rebuilt with a new instance leaves none behind. */
    private final ThreadPoolExecutor pool = newPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Info> cache = new HashMap<String, Info>();
    /** Barcodes being looked up, with everyone waiting for them; main thread only, like cache. */
    private final Map<String, List<InfoCallback>> pending = new HashMap<String, List<InfoCallback>>();
    /** When the last request to any database started (System.nanoTime), for MIN_GAP_MS. */
    private static final Object gapLock = new Object();
    private static long lastStart;
    private static boolean started;

    private static ThreadPoolExecutor newPool() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(PARALLEL, PARALLEL, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    String cached(String code) {
        Info hit = cache.get(code);
        return hit != null ? hit.name : null;
    }

    Info cachedInfo(String code) {
        return cache.get(code);
    }

    /** True for the answer "none of the databases knows this barcode". */
    static boolean isNotFound(Info info) {
        return info != null && info.name == null && info.categories != null && info.categories.isEmpty()
                && info.summaryText == null;
    }

    /**
     * A fresh not-found answer: empty lists, never null, so the checks that read them are safe.
     * Its source is the last database asked; it is not FOOD, so no Open Food Facts rule reads it.
     */
    private static Info notFound() {
        return new Info(null, new ArrayList<String>(), Source.PRODUCTS);
    }

    void lookup(final String code, final Callback callback) {
        lookupInfo(code, new InfoCallback() {
            @Override public void onInfo(String forCode, Info info) {
                if (info != null && info.name != null) callback.onName(forCode, info.name);
            }
        });
    }

    void lookupInfo(final String code, final InfoCallback callback) {
        Info hit = cache.get(code);
        if (hit != null) {
            callback.onInfo(code, hit);
            return;
        }
        List<InfoCallback> waiting = pending.get(code);
        if (waiting != null) {             // already on its way: wait for that answer
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<InfoCallback>();
        waiting.add(callback);
        pending.put(code, waiting);
        pool.execute(new Runnable() {
            @Override public void run() {
                final Info info = fetchAnywhere(code);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (info != null) cache.put(code, info);
                        List<InfoCallback> all = pending.remove(code);
                        if (all != null) for (InfoCallback c : all) c.onInfo(code, info);
                    }
                });
            }
        });
    }

    private Info fetchAnywhere(String code) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_DEADLINE_MS);
        Info info = fetch(Source.FOOD, code, deadline);
        if (info == NOT_FOUND) info = fetch(Source.BEAUTY, code, deadline);
        if (info == NOT_FOUND) info = fetch(Source.PRODUCTS, code, deadline);
        return info == NOT_FOUND ? notFound() : info;
    }

    /** Milliseconds left until `deadline` (System.nanoTime), at least 0. */
    private static long left(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    /** Waits until the databases may be asked again; false when that would pass the deadline. */
    private static boolean waitTurn(long deadline) throws InterruptedException {
        long wait;
        synchronized (gapLock) {
            long now = System.nanoTime();
            long at = started ? Math.max(now, lastStart + TimeUnit.MILLISECONDS.toNanos(MIN_GAP_MS)) : now;
            if (at - deadline >= 0) return false;
            lastStart = at;
            started = true;
            wait = at - now;
        }
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
        return true;
    }

    /** The product, NOT_FOUND when the database says it has none, or null when nothing came. */
    private Info fetch(Source source, String code, long deadline) {
        HttpURLConnection conn = null;
        try {
            if (!waitTurn(deadline)) return null;
            String base = testBase != null ? testBase : "https://" + source.host;
            URL url = new URL(base + "/api/v2/product/" + code
                    + ".json?fields=product_name,brands,quantity,categories_tags,packagings,packaging_tags,labels_tags");
            long left = left(deadline);
            if (left <= 0) return null;
            conn = (HttpURLConnection) url.openConnection();
            // Connect and the first read share what is left, so the deadline holds for both.
            int c = (int) Math.max(1, Math.min(CONNECT_TIMEOUT_MS, left / 2));
            int r = (int) Math.max(1, Math.min(READ_TIMEOUT_MS, left - c));
            conn.setConnectTimeout(c);
            conn.setReadTimeout(r);
            conn.setRequestProperty("User-Agent", "PfandCounter/1.0 (Android; +https://github.com/passport0819/pfand-counter)");
            int status = conn.getResponseCode();
            if (status == 404) return NOT_FOUND;
            if (status != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 256 * 1024) {
                buf.write(chunk, 0, n);
                if (left(deadline) <= 0) {          // a trickling answer stops at the deadline
                    in.close();
                    return null;
                }
            }
            in.close();
            JSONObject root = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            if (root.optInt("status") != 1) return NOT_FOUND;
            JSONObject p = root.optJSONObject("product");
            if (p == null) return null;
            List<String> categories = new ArrayList<String>();
            JSONArray tags = p.optJSONArray("categories_tags");
            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) categories.add(tags.optString(i));
            }
            String name = compose(p.optString("brands", ""), p.optString("product_name", ""),
                    p.optString("quantity", ""));
            if (name == null && categories.isEmpty()) return null;
            Info info = new Info(name, categories, source);
            info.summaryText = describe(p, categories);
            info.packaging = packagingTags(p);
            info.productName = p.optString("product_name", "");
            info.quantity = p.optString("quantity", "");
            JSONArray labels = p.optJSONArray("labels_tags");
            if (labels != null) {
                for (int i = 0; i < labels.length(); i++) info.labels.add(labels.optString(i));
            }
            return info;
        } catch (Exception e) {
            Log.i(Store.TAG, source.label + " lookup failed for " + code + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** As tools/dairy-jar-measure.py reads them: every part's material, shape, recycling, then the tags. */
    static List<String> packagingTags(JSONObject p) {
        List<String> out = new ArrayList<String>();
        JSONArray parts = p.optJSONArray("packagings");
        for (String field : new String[]{"material", "shape", "recycling"}) {
            if (parts == null) break;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                String value = part == null ? "" : part.optString(field, "");
                if (!value.isEmpty()) out.add(value);
            }
        }
        JSONArray tags = p.optJSONArray("packaging_tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) out.add(tags.optString(i));
        }
        return out;
    }

    /**
     * The product in one line, built exactly like describe() in tools/seed-open.py: name — size —
     * packaging words — category. Null without a product name, because then there is nothing to
     * read.
     */
    static String describe(JSONObject p, List<String> categories) {
        String brands = p.optString("brands", "");
        String brand = (brands.contains(",") ? brands.substring(0, brands.indexOf(',')) : brands).trim();
        String name = p.optString("product_name", "").trim();
        if (!brand.isEmpty() && !name.toLowerCase().contains(brand.toLowerCase())) {
            name = (brand + " " + name).trim();
        }
        name = name.replaceAll("\\s+", " ");
        if (name.length() > 46) name = name.substring(0, 46);
        if (name.isEmpty()) return null;
        StringBuilder line = new StringBuilder(name);
        String quantity = p.optString("quantity", "").trim();
        if (!quantity.isEmpty()) line.append(" — ").append(quantity);

        List<String> words = new ArrayList<String>();
        JSONArray parts = p.optJSONArray("packagings");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                for (String field : new String[]{"shape", "material", "recycling"}) {
                    String value = part.optString(field, "");
                    if (!value.isEmpty()) words.add(tagWord(value));
                }
            }
        }
        JSONArray tags = p.optJSONArray("packaging_tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) words.add(tagWord(tags.optString(i)));
        }
        List<String> seen = new ArrayList<String>();
        List<String> packaging = new ArrayList<String>();
        for (String word : words) {
            if (!seen.contains(word.toLowerCase())) {
                seen.add(word.toLowerCase());
                packaging.add(word);
            }
        }
        if (!packaging.isEmpty()) {
            line.append(" — ").append(join(packaging.subList(0, Math.min(8, packaging.size()))));
        }
        List<String> english = new ArrayList<String>();
        for (String c : categories) {
            if (c.startsWith("en:") && english.size() < 3) english.add(tagWord(c));
        }
        if (!english.isEmpty()) line.append(" — category: ").append(join(english));
        return line.toString();
    }

    /** "en:clear-glass" → "clear glass" */
    private static String tagWord(String tag) {
        int colon = tag.indexOf(':');
        return (colon >= 0 ? tag.substring(colon + 1) : tag).replace('-', ' ');
    }

    private static String join(List<String> words) {
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (b.length() > 0) b.append(", ");
            b.append(w);
        }
        return b.toString();
    }

    /** "Brand Name, 0.5 l" — brand only when the product name does not already say it. */
    private static String compose(String brands, String product, String quantity) {
        String brand = brands.contains(",") ? brands.substring(0, brands.indexOf(',')).trim() : brands.trim();
        String name = product.trim();
        if (name.isEmpty()) name = brand;
        else if (!brand.isEmpty() && !name.toLowerCase().contains(brand.toLowerCase())) {
            name = brand + " " + name;
        }
        if (name.isEmpty()) return null;
        String q = quantity.trim();
        if (!q.isEmpty() && !q.equalsIgnoreCase("null")) name = name + ", " + q;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
}

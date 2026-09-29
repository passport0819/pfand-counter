package app.pfandcounter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints, per saved Open Food Facts answer, the line the app sends to Jev and the request body,
 * so tools/jev-text-check.py can hold them against describe() in tools/seed-open.py.
 * Runs on the laptop with the real org.json from core-for-system-modules.jar:
 *   java -cp OUT:build/classes:core-for-system-modules.jar:android.jar app.pfandcounter.JevTextTest DIR
 */
public class JevTextTest {
    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<Path>();
        for (Path p : Files.newDirectoryStream(Paths.get(args[0]), "*.json")) files.add(p);
        java.util.Collections.sort(files);
        for (Path f : files) {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f), "UTF-8"));
            JSONObject p = root.optJSONObject("product");
            if (p == null) continue;
            List<String> categories = new ArrayList<String>();
            JSONArray tags = p.optJSONArray("categories_tags");
            if (tags != null) for (int i = 0; i < tags.length(); i++) categories.add(tags.optString(i));
            String text = ProductLookup.describe(p, categories);
            JSONObject out = new JSONObject();
            out.put("code", root.optString("code"));
            out.put("text", text);
            out.put("body", new JSONObject(JevClient.requestBody(text)));
            out.put("drink", new JSONObject(JevClient.drinkRequestBody(text)));
            System.out.println(out.toString());
        }
    }
}

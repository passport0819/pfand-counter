package app.pfandcounter;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

/**
 * Optional features of a local build. The published app ships without any: Loader then hands out
 * NONE, which adds nothing anywhere. A local build may add a class app.pfandcounter.LocalAddon
 * implementing this interface (src-local/ when built with -PwithLocal), the same way as
 * ExtraSource. All calls come on the main thread.
 */
interface Addon {

    /** Once, in MainActivity.onCreate. */
    void attach(MainActivity activity);

    /** The new-barcode window has just opened; known is the product data already at hand, or null. */
    void onCodeDialog(AlertDialog dialog, View content, String code, ProductLookup.Info known);

    /**
     * Product data for that window are there, after MainActivity showed its own hints. info is
     * null when the lookup brought nothing back.
     */
    void onProductInfo(AlertDialog dialog, View content, String code, ProductLookup.Info info);

    /** The text of a button of its own in Settings, or 0 for none. */
    int settingsLabel();

    /** That button's picture. */
    int settingsIcon();

    /** Opens what that button opens. */
    void showSettings();

    /** Extra cards for "How the app works", each {picture, title, text}. */
    int[][] howToCards();

    /** Extra cards for the privacy notice, each {picture, title, text}. */
    int[][] privacyCards();

    /** Extra lines for the statistics page; sample: LayoutCheck's longest realistic content. */
    void addStats(Context ctx, LinearLayout wrap, boolean sample);

    /** LayoutCheck: whatever it adds to the new-barcode window, with its longest texts. */
    void layoutSample(Context ctx, View dialogRoot);

    /** The published app: adds nothing. */
    Addon NONE = new Addon() {
        @Override public void attach(MainActivity activity) { }
        @Override public void onCodeDialog(AlertDialog dialog, View content, String code, ProductLookup.Info known) { }
        @Override public void onProductInfo(AlertDialog dialog, View content, String code, ProductLookup.Info info) { }
        @Override public int settingsLabel() { return 0; }
        @Override public int settingsIcon() { return 0; }
        @Override public void showSettings() { }
        @Override public int[][] howToCards() { return new int[0][]; }
        @Override public int[][] privacyCards() { return new int[0][]; }
        @Override public void addStats(Context ctx, LinearLayout wrap, boolean sample) { }
        @Override public void layoutSample(Context ctx, View dialogRoot) { }
    };

    final class Loader {
        private Loader() {
        }

        /** The local build's features, or NONE when this build has none. */
        static Addon load() {
            try {
                Object o = Class.forName("app.pfandcounter.LocalAddon").getDeclaredConstructor().newInstance();
                Log.i(Store.TAG, "local features: " + o.getClass().getSimpleName());
                return (Addon) o;
            } catch (ClassNotFoundException e) {
                return NONE;
            } catch (Exception e) {
                Log.w(Store.TAG, "local features unusable", e);
                return NONE;
            }
        }
    }
}

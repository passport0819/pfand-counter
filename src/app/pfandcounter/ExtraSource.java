package app.pfandcounter;

import android.util.Log;

/**
 * An optional second source of deposits, asked for a barcode that neither the shipped list nor
 * the user's answers know, before Open Food Facts. The app ships without one. A local build may
 * add a class app.pfandcounter.LocalSource implementing this interface (build.sh compiles
 * src-local/ when it exists); its answer is counted like a list entry and remembered.
 */
interface ExtraSource {

    /** A deposit the source states for one barcode: 8, 15 or 25 cents, and a product name. */
    final class Answer {
        final int cents;
        final String name;

        Answer(int cents, String name) {
            this.cents = cents;
            this.name = name == null ? "" : name;
        }
    }

    /**
     * Blocking, called off the main thread. Null when the source does not know the barcode, is
     * unsure, or cannot be reached — the app then goes on as if there were no extra source.
     */
    Answer ask(String code) throws Exception;

    final class Loader {
        private Loader() {
        }

        /** The local source, or null when this build has none. */
        static ExtraSource load() {
            try {
                Object o = Class.forName("app.pfandcounter.LocalSource").getDeclaredConstructor().newInstance();
                Log.i(Store.TAG, "extra deposit source: " + o.getClass().getSimpleName());
                return (ExtraSource) o;
            } catch (ClassNotFoundException e) {
                return null;
            } catch (Exception e) {
                Log.w(Store.TAG, "extra deposit source unusable", e);
                return null;
            }
        }
    }
}

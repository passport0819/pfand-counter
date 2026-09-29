package app.pfandcounter;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Plain files in the app's private directory. No database, nothing to migrate. */
class Store {
    static final String TAG = "PfandCounter";
    private final File dir;

    Store(Context ctx) {
        dir = ctx.getFilesDir();
    }

    String read(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + name, e);
            return null;
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) { }
        }
    }

    /** Writes via a temporary file so a crash mid-write cannot shred the data. */
    void write(String name, String content) {
        File tmp = new File(dir, name + ".tmp");
        File target = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(target)) Log.w(TAG, "cannot rename " + tmp + " to " + target);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + name, e);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    boolean exists(String name) {
        return new File(dir, name).exists();
    }

    void delete(String name) {
        File f = new File(dir, name);
        if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
    }
}

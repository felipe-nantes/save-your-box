package io.freemytv;

import android.content.Context;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Your photos for the screensaver, sent from the phone remote: kept in the app's own storage (no permission needed),
 * read by the screensaver in the :home process. Up to MAX; the oldest go first.
 */
final class Photos {
    private Photos() {}

    static final int MAX = 60;
    static final int MAX_BYTES = 15 * 1024 * 1024;

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "photos");
    }

    /** The photos, oldest first (the names are their arrival times). */
    static List<File> list(Context c) {
        File[] files = dir(c).listFiles((d, name) -> name.endsWith(".jpg") && !name.startsWith("."));
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        out.addAll(Arrays.asList(files));
        return out;
    }

    /** The screensaver's slides: one Pick per photo, with no caption (kind "photo", the file's name). */
    static List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        for (File f : list(c)) {
            Recs.Pick p = new Recs.Pick();
            p.kind = PHOTO;
            p.id = f.getName().hashCode();
            p.title = "";
            p.backdrop = f.getName();
            out.add(p);
        }
        return out;
    }

    static final String PHOTO = "photo";

    static File file(Context c, Recs.Pick p) {
        File f = new File(dir(c), p.backdrop);
        return f.exists() ? f : null;
    }

    /** Saves an image of `length` bytes; false if it isn't one Android can show. Keeps at most MAX. */
    static synchronized boolean save(Context c, InputStream in, int length) throws IOException {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no photo folder");
        File tmp = new File(d, ".incoming");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int left = length;
            while (left > 0) {
                int n = in.read(buffer, 0, Math.min(buffer.length, left));
                if (n < 0) throw new IOException("upload cut short");
                out.write(buffer, 0, n);
                left -= n;
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            tmp.delete();
            return false;
        }
        File f = new File(d, System.currentTimeMillis() + ".jpg");
        if (!tmp.renameTo(f)) throw new IOException("could not save the photo");
        List<File> all = list(c);
        for (int i = 0; i < all.size() - MAX; i++) all.get(i).delete();
        return true;
    }

    static synchronized void clear(Context c) {
        for (File f : list(c)) f.delete();
    }
}

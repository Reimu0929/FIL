package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 全画面表示用のディスクキャッシュ。画面サイズに縮小した JPEG を
 * サムネイルと同じフォルダに保存する。メモリに全部載せずに先読みできる。
 */
final class ViewCache {
    private ViewCache() {}

    static synchronized void init(Context c) {
        ThumbCache.init(c.getApplicationContext());
    }

    private static File fileFor(Photos.Item it, int px) {
        File d = ThumbCache.dir();
        if (d == null) return null;
        String key = it.path + "|" + it.size + "|" + it.modified + "|v" + px;
        return new File(d, "v" + ThumbCache.sha1(key) + ".jpg");
    }

    static boolean has(Photos.Item it, int px) {
        File f = fileFor(it, px);
        return f != null && f.exists();
    }

    /** ディスクにあれば読み込み、なければ作って保存する(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px) {
        File f = fileFor(it, px);
        if (f != null && f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
            f.delete();
        }
        Bitmap b = generate(it, px);
        if (b != null) write(f, b);
        return b;
    }

    /** まだ無ければディスクに作る(メモリには残さない)。 */
    static void ensure(Photos.Item it, int px) {
        File f = fileFor(it, px);
        if (f == null || f.exists()) return;
        Bitmap b = generate(it, px);
        if (b == null) return;
        write(f, b);
        b.recycle();
    }

    private static Bitmap generate(Photos.Item it, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.path, o);
            int big = Math.max(o.outWidth, o.outHeight);
            if (big <= 0) return null;
            int s = 1;
            while (big / (s * 2) >= px) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(it.path, o);
            if (b == null) return null;
            if (it.rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(it.rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File f, Bitmap b) {
        if (f == null) return;
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, o);
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}

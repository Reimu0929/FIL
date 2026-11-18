package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

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
        ensure(it, px, false);
    }

    /** 未作成のものだけ、または regenerateAll が true なら全件をディスクに作る。 */
    static void ensure(Photos.Item it, int px, boolean regenerateAll) {
        File f = fileFor(it, px);
        if (f == null || (!regenerateAll && f.exists())) return;
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
            // inSampleSize だけでは画面長辺の約2倍が残ることがあるため、必ず画面サイズまで縮小する。
            int decodedBig = Math.max(b.getWidth(), b.getHeight());
            if (decodedBig > px) {
                float scale = (float) px / decodedBig;
                Bitmap r = Bitmap.createScaledBitmap(b,
                        Math.max(1, Math.round(b.getWidth() * scale)),
                        Math.max(1, Math.round(b.getHeight() * scale)), true);
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
        File tmp = new File(parent, f.getName() + "." + UUID.randomUUID() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 85, o)) {
                throw new java.io.IOException("JPEG compression failed");
            }
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        try {
            // 同じディレクトリ内で原子的に置き換える。再生成時も既存ファイルへ上書きできる。
            Os.rename(tmp.getAbsolutePath(), f.getAbsolutePath());
        } catch (Exception e) {
            tmp.delete();
        }
    }
}

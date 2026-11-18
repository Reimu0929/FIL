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

    private static boolean legacyCleanupStarted;

    static synchronized void init(Context c) {
        ThumbCache.init(c.getApplicationContext());
        if (legacyCleanupStarted) return;
        legacyCleanupStarted = true;
        new Thread(ViewCache::deleteLegacyCache).start();
    }

    private static File fileFor(Photos.Item it, int px) {
        File d = ThumbCache.dir();
        if (d == null) return null;
        // v1 には画面長辺まで縮小した低解像度キャッシュが混在するため、v2 へ移行する。
        String key = it.path + "|" + it.size + "|" + it.modified + "|v2|" + px;
        return new File(d, "v2" + ThumbCache.sha1(key) + ".jpg");
    }

    /** 旧形式v1の全画面キャッシュを削除する。v2 とは名前が違うため並行生成してよい。 */
    private static void deleteLegacyCache() {
        File d = ThumbCache.dir();
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String name = f.getName();
            // v1 は "v" + 40桁のSHA-1 + ".jpg"。先頭が v2 の旧ファイルも確実に削除する。
            if (name.matches("v[0-9a-f]{40}\\.jpg")) f.delete();
        }
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
            // 画面長辺への追加縮小は行わず、サンプリング後の解像度を保持して画質を優先する。
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

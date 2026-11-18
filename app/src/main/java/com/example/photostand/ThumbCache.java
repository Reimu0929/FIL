package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Environment;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * サムネイルのキャッシュ。メモリ(アプリ全体で共有)とディスクの2段構え。
 * ディスクの保存先は、SDカードが入っていればSDカード、なければ本体。
 */
final class ThumbCache {
    private ThumbCache() {}

    /** サイズ段階: 小・中・大 */
    static final int[] TIER_PX = {160, 256, 384};
    static final int[] TIER_DP = {110, 150, 220};
    static final String[] TIER_NAME = {"小", "中", "大"};

    private static LruCache<String, Bitmap> mem;
    private static File dir;
    private static Context app;
    static String where = "本体";

    static synchronized void init(Context ctx) {
        if (mem != null) return;
        app = ctx.getApplicationContext();
        mem = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        File base = null;
        try {
            for (File f : app.getExternalCacheDirs()) {
                if (f != null && Environment.isExternalStorageRemovable(f)
                        && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(f))) {
                    base = f;
                    where = "SDカード";
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (base == null) base = app.getExternalCacheDir();
        if (base == null) base = app.getCacheDir();
        dir = new File(base, "thumbs");
        dir.mkdirs();
        new Thread(ThumbCache::pruneNow).start();
    }

    static int tier(Context c) {
        int t = Prefs.sp(c).getInt("tier", 1);
        return Math.max(0, Math.min(t, TIER_PX.length - 1));
    }

    static void setTier(Context c, int t) {
        Prefs.sp(c).edit().putInt("tier", t).apply();
    }

    /** サムネイルと全画面用キャッシュが共有するディレクトリ。 */
    static File dir() {
        return dir;
    }

    private static String memKey(Photos.Item it, int px) {
        return it.path + "|" + it.size + "|" + it.modified + "|" + px;
    }

    private static File fileFor(Photos.Item it, int px) {
        return new File(dir, sha1(memKey(it, px)) + ".jpg");
    }

    /** メモリにあれば返す(画面スレッドから呼んでよい)。 */
    static Bitmap memGet(Photos.Item it, int px) {
        return mem.get(memKey(it, px));
    }

    /** メモリ内のサムネイル数。 */
    static int memCount() {
        return mem == null ? 0 : mem.snapshot().size();
    }

    /** メモリ → ディスク → 新規作成の順に探す(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px) {
        String k = memKey(it, px);
        Bitmap b = mem.get(k);
        if (b != null) return b;
        File f = fileFor(it, px);
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                mem.put(k, b);
                return b;
            }
            f.delete();
        }
        b = generate(it, px);
        if (b != null) {
            mem.put(k, b);
            write(f, b);
        }
        return b;
    }

    /**
     * 3つのサイズのうち、まだ無いものをディスクに作る(メモリには入れない)。
     * 一番大きいサイズで1回だけ読み込み、そこから小さいサイズを作るので速い。
     */
    static void ensureAll(Photos.Item it) {
        boolean[] need = new boolean[TIER_PX.length];
        int top = -1;
        for (int t = 0; t < TIER_PX.length; t++) {
            need[t] = !fileFor(it, TIER_PX[t]).exists();
            if (need[t]) top = t;
        }
        if (top < 0) return;
        Bitmap base = generate(it, TIER_PX[top]);
        if (base == null) return;
        for (int t = 0; t < TIER_PX.length; t++) {
            if (!need[t]) continue;
            Bitmap b = (t == top) ? base : scaleShort(base, TIER_PX[t], 1f);
            write(fileFor(it, TIER_PX[t]), b);
            if (b != base) b.recycle();
        }
        base.recycle();
    }

    private static void write(File f, Bitmap b) {
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, o);
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** 短辺が px の tol 倍を超えていたら、短辺 px に縮小して返す(元の画像は壊さない)。 */
    private static Bitmap scaleShort(Bitmap b, int px, float tol) {
        int shortSide = Math.min(b.getWidth(), b.getHeight());
        if (shortSide <= px * tol) return b;
        float sc = (float) px / shortSide;
        return Bitmap.createScaledBitmap(b,
                Math.max(1, Math.round(b.getWidth() * sc)),
                Math.max(1, Math.round(b.getHeight() * sc)), true);
    }

    private static Bitmap generate(Photos.Item it, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.path, o);
            int s = 1;
            while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2;
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
            Bitmap r = scaleShort(b, px, 1.2f);
            if (r != b) b.recycle();
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** ディスクのキャッシュ: {合計バイト数, ファイル数}。 */
    static long[] diskStats() {
        long bytes = 0, count = 0;
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                bytes += f.length();
                count++;
            }
        }
        return new long[]{bytes, count};
    }

    /** ディスクとメモリのキャッシュをすべて消す。 */
    static void clearDisk() {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        if (mem != null) mem.evictAll();
    }

    /** 上限を超えていたら、古いものから消して上限の8割にする。 */
    static void pruneNow() {
        try {
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            long limit = Prefs.limitMb(app) * 1024L * 1024L;
            long keep = limit / 5 * 4;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= limit) return;
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) {
                if (total <= keep) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) {
        }
    }
}

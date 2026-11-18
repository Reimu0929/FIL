package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Environment;
import android.system.Os;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static File dir;      // サムネイル用
    private static File fullDir;  // 全画面用(専用フォルダ)
    private static Context app;
    static String where = "本体";

    /** LruCache.snapshot() でマップ全体をコピーせずに枚数を追跡する。 */
    private static final AtomicInteger memCount = new AtomicInteger();
    /** キー単位の single-flight。同じ画像の同時デコード・同時JPEG書き込みを防ぐ。 */
    private static final ConcurrentHashMap<String, Future<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private static final Set<String> allInFlight = ConcurrentHashMap.newKeySet();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static synchronized void init(Context ctx) {
        if (mem != null) return;
        app = ctx.getApplicationContext();
        mem = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
            @Override protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                memCount.decrementAndGet();
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
        fullDir = new File(base, "full");
        fullDir.mkdirs();
        new Thread(() -> {
            migrateFullCache();
            pruneNow();
        }).start();
    }

    /** 旧バージョンは全画面用も thumbs に混在していたので、full フォルダへ移行する。 */
    private static void migrateFullCache() {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null || fullDir == null) return;
        for (File f : fs) {
            String name = f.getName();
            if (name.matches("v[0-9a-f]{40}\\.jpg")) {
                // v1 の全画面キャッシュは旧形式なので削除。
                f.delete();
            } else if (name.matches("v2[0-9a-f]{40}\\.jpg")) {
                File dst = new File(fullDir, name);
                if (!dst.exists()) f.renameTo(dst);
                else f.delete();
            }
        }
    }

    static int tier(Context c) {
        int t = Prefs.sp(c).getInt("tier", 1);
        return Math.max(0, Math.min(t, TIER_PX.length - 1));
    }

    static void setTier(Context c, int t) {
        Prefs.sp(c).edit().putInt("tier", t).apply();
    }

    /** サムネイル用のディレクトリ。 */
    static File dir() {
        return dir;
    }

    /** 全画面用キャッシュのディレクトリ。 */
    static File fullDir() {
        return fullDir;
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

    /** メモリ内のサムネイル数(snapshot を作らずカウンタで返す)。 */
    static int memCount() {
        return memCount.get();
    }

    /** メモリ内のサムネイルが占めるバイト数。 */
    static int memBytes() {
        return mem == null ? 0 : mem.size();
    }

    /** メモリ → ディスク → 新規作成の順に探す(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px) {
        String k = memKey(it, px);
        Bitmap b = mem.get(k);
        if (b != null) return b;
        FutureTask<Bitmap> task = new FutureTask<>(() -> loadOrCreate(k, it, px));
        Future<Bitmap> f = inFlight.putIfAbsent(k, task);
        if (f == null) {
            f = task;
            try {
                task.run();
            } finally {
                inFlight.remove(k, task);
            }
        }
        try {
            return f.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 同じキーの生成を1回だけ行う(single-flight の実体)。 */
    private static Bitmap loadOrCreate(String k, Photos.Item it, int px) {
        File f = fileFor(it, px);
        if (!f.exists()) {
            // 小さい画像では段階ファイルを省くため、最大段のファイルを代用する。
            File largest = fileFor(it, TIER_PX[TIER_PX.length - 1]);
            if (largest.exists()) f = largest;
        }
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b != null) {
            memPut(k, b);
            return b;
        }
        b = generate(it, px);
        if (b != null) {
            memPut(k, b);
            write(fileFor(it, px), b);
        }
        return b;
    }

    private static void memPut(String k, Bitmap b) {
        mem.put(k, b);
        memCount.incrementAndGet();
    }

    /** 未作成のものだけ、または regenerateAll が true なら全サイズを作り直す。 */
    static void ensureAll(Photos.Item it, boolean regenerateAll) {
        String allKey = it.path + "|" + it.size + "|" + it.modified;
        if (!allInFlight.add(allKey)) return;
        try {
            int largest = TIER_PX.length - 1;
            int shortSide = sourceShortSide(it);
            boolean[] need = new boolean[TIER_PX.length];
            int top = -1;
            for (int t = 0; t < TIER_PX.length; t++) {
                // ソースより大きい段は縮小されず同一内容になるので、最大段だけを持つ。
                boolean meaningful = (t == largest) || shortSide > TIER_PX[t];
                need[t] = meaningful && (regenerateAll || !fileFor(it, TIER_PX[t]).exists());
                if (need[t]) top = t;
            }
            if (top < 0) return;
            Bitmap base = generate(it, TIER_PX[top]);
            if (base == null) return;
            for (int t = 0; t < TIER_PX.length; t++) {
                if (!need[t]) continue;
                Bitmap b = (t == top) ? base : scaleShort(base, TIER_PX[t], 1f);
                // 縮小されず上位段と同一内容になるファイルは作らない(読み出し時に最大段へフォールバック)。
                if (b == base && t != top) continue;
                write(fileFor(it, TIER_PX[t]), b);
                if (b != base) b.recycle();
            }
            base.recycle();
        } finally {
            allInFlight.remove(allKey);
        }
    }

    /** 元画像の短辺(回転しても短辺は変わらない)。段階の要否判定に使う。 */
    private static int sourceShortSide(Photos.Item it) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.path, o);
            return Math.min(o.outWidth, o.outHeight);
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    private static void write(File f, Bitmap b) {
        File tmp = new File(f.getParentFile(), f.getName() + "." + UUID.randomUUID() + ".tmp");
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
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                int v = x & 0xFF;
                sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 完成したJPEGだけを数えたディスクキャッシュの内訳。 */
    static final class DiskStats {
        long thumbBytes, viewBytes;
        int thumbFiles, viewFiles;

        long totalBytes() { return thumbBytes + viewBytes; }
        int totalFiles() { return thumbFiles + viewFiles; }
    }

    /** 両方のフォルダのファイルをまとめて列挙する。 */
    private static File[] allFiles(boolean jpgOnly) {
        ArrayList<File> out = new ArrayList<>();
        for (File d : new File[]{dir, fullDir}) {
            File[] fs = d == null ? null : d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!f.isFile()) continue;
                if (jpgOnly && !f.getName().endsWith(".jpg")) continue;
                out.add(f);
            }
        }
        return out.toArray(new File[0]);
    }

    static DiskStats diskStats() {
        DiskStats st = new DiskStats();
        for (File f : allFiles(true)) {
            // 移行前の名前に応じても分類できるよう、接頭辞でも判別する。
            if (f.getName().startsWith("v")) {
                st.viewBytes += f.length();
                st.viewFiles++;
            } else {
                st.thumbBytes += f.length();
                st.thumbFiles++;
            }
        }
        return st;
    }

    /** ディスクとメモリのキャッシュをすべて消す。 */
    static void clearDisk() {
        for (File f : allFiles(false)) f.delete();
        if (mem != null) mem.evictAll();
    }

    /** 書き込み中断・プロセスkillで残った古い .tmp を掃除する(完成済みキャッシュは触らない)。 */
    private static void deleteStaleTemp() {
        long cutoff = System.currentTimeMillis() - 60L * 60 * 1000;
        for (File f : allFiles(false)) {
            if (!f.getName().endsWith(".tmp")) continue;
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    /** 上限を超えていたら、両フォルダの古い完成済みキャッシュから消して上限の8割にする。 */
    static synchronized void pruneNow() {
        try {
            deleteStaleTemp();
            File[] all = allFiles(true);
            if (all.length == 0) return;
            long limit = Prefs.limitMb(app) * 1024L * 1024L;
            long keep = limit / 5 * 4;
            long total = 0;
            ArrayList<File> complete = new ArrayList<>();
            for (File f : all) {
                // 書き込み中の .tmp を消すとキャッシュ生成が失敗するため、完成したJPEGだけを扱う。
                complete.add(f);
                total += f.length();
            }
            File[] fs = complete.toArray(new File[0]);
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

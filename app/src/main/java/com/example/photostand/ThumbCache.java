package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Environment;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    // RAM キャッシュは RamBudget.LOCK で保護し、アクセス順に古いものから外す。
    private static final LinkedHashMap<String, Thumbnail> mem = new LinkedHashMap<>(16, 0.75f, true);
    private static long memBytes;
    private static String currentBucket;
    private static ViewerActivity viewer;
    private static boolean initialized;

    private static final class Thumbnail {
        final String bucket;
        final Bitmap bitmap;

        Thumbnail(String bucket, Bitmap bitmap) {
            this.bucket = bucket;
            this.bitmap = bitmap;
        }
    }
    private static File dir;      // サムネイル用
    private static File fullDir;  // 全画面用(専用フォルダ)
    private static Context app;
    static String where = "本体";

    /** キー単位の single-flight。同じ画像の同時デコード・同時JPEG書き込みを防ぐ。 */
    private static final ConcurrentHashMap<String, Future<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private static final Set<String> allInFlight = ConcurrentHashMap.newKeySet();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 全削除と rename を互斥するロック。 */
    private static final Object diskLock = new Object();
    /** 全削除のたびに進む世代。書き込み開始時と不同ならコミットしない。 */
    private static volatile long diskGeneration;
    private static final AtomicInteger writesSincePrune = new AtomicInteger();

    static synchronized void init(Context ctx) {
        if (initialized) return;
        app = ctx.getApplicationContext();
        RamBudget.configure(app);
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
        initialized = true;
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

    /** 全画面用キャッシュのディレクトリ。 */
    static File fullDir() {
        return fullDir;
    }

    private static String memKey(Photos.Item it, int px) {
        return it.path + "|" + it.size + "|" + px;
    }

    private static File fileFor(Photos.Item it, int px) {
        return new File(dir, sha1(memKey(it, px)) + ".jpg");
    }

    static void setCurrentBucket(String bucket) {
        synchronized (RamBudget.LOCK) {
            if (viewer == null) currentBucket = bucket;
        }
    }

    static void enterViewer(ViewerActivity activity, String bucket) {
        synchronized (RamBudget.LOCK) {
            viewer = activity;
            currentBucket = bucket;
            trimRam(false, Long.MAX_VALUE);
        }
    }

    static void leaveViewer(ViewerActivity activity) {
        synchronized (RamBudget.LOCK) {
            if (viewer == activity) viewer = null;
        }
    }

    /** RamBudget.LOCK の保持中に呼ぶ。 */
    static long trimRam(boolean current, long bytes) {
        long removed = 0;
        Iterator<Map.Entry<String, Thumbnail>> entries = mem.entrySet().iterator();
        while (entries.hasNext() && removed < bytes) {
            Thumbnail entry = entries.next().getValue();
            if (Objects.equals(currentBucket, entry.bucket) != current) continue;
            removed += entry.bitmap.getByteCount();
            entries.remove();
        }
        memBytes -= removed;
        return removed;
    }

    static Bitmap memGet(Photos.Item it, int px) {
        synchronized (RamBudget.LOCK) {
            Thumbnail entry = mem.get(memKey(it, px));
            return entry == null ? null : entry.bitmap;
        }
    }

    static int memCount() {
        synchronized (RamBudget.LOCK) { return mem.size(); }
    }

    static long memBytes() {
        synchronized (RamBudget.LOCK) { return memBytes; }
    }

    /** メモリ → ディスク → 新規作成の順に探す(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px) {
        String k = memKey(it, px);
        Bitmap b = memGet(it, px);
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
        int priority;
        synchronized (RamBudget.LOCK) {
            priority = Objects.equals(currentBucket, it.bucket) ? RamBudget.CURRENT_THUMB : RamBudget.OTHER_THUMB;
        }
        Bitmap b = f.exists() ? decodeCached(f, priority) : null;
        if (b != null) {
            memPut(k, it.bucket, b);
            return b;
        }
        if (Thread.currentThread().isInterrupted()) return null;
        b = generateForDisk(it, px, true, RamBudget.GENERATING, fileFor(it, px));
        if (b != null) memPut(k, it.bucket, b);
        return b;
    }

    private static void memPut(String key, String bucket, Bitmap bitmap) {
        synchronized (RamBudget.LOCK) {
            boolean current = Objects.equals(currentBucket, bucket);
            if ((viewer != null && !current) || mem.containsKey(key)) return;
            int priority = current ? RamBudget.CURRENT_THUMB : RamBudget.OTHER_THUMB;
            int bytes = bitmap.getByteCount();
            if (bytes > RamBudget.limit()) return;
            if (!RamBudget.makeRoom(bytes, priority)) {
                trimRam(current, bytes);
                if (!RamBudget.makeRoom(bytes, priority)) return;
            }
            mem.put(key, new Thumbnail(bucket, bitmap));
            memBytes += bytes;
        }
    }

    /** 未作成のものだけ、または regenerateAll が true なら全サイズを作り直す。 */
    static void ensureAll(Photos.Item it, boolean regenerateAll) {
        String allKey = it.path + "|" + it.size;
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
            int topTier = top;
            Bitmap base = decodeScaled(it, TIER_PX[topTier], true, 1.2f, RamBudget.GENERATING, image -> {
                for (int tier = 0; tier < TIER_PX.length; tier++) {
                    if (!need[tier]) continue;
                    Bitmap scaled = tier == topTier ? image : scaleShort(image, TIER_PX[tier], 1f);
                    if (scaled == image && tier != topTier) continue;
                    try {
                        writeJpeg(fileFor(it, TIER_PX[tier]), scaled);
                    } finally {
                        if (scaled != image) scaled.recycle();
                    }
                }
            });
            if (base != null) base.recycle();
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

    /** サムネイル/全画面どちらもここで書き込む。圧縮はロック外、rename だけロック内で行う。 */
    private static void writeJpeg(File f, Bitmap b) {
        final long gen = diskGeneration;
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
        boolean committed;
        synchronized (diskLock) {
            if (gen != diskGeneration) {
                committed = false;   // 圧縮中に全削除が走った
            } else {
                try {
                    // 同じディレクトリ内で原子的に置き換える。再生成時も既存ファイルへ上書きできる。
                    Os.rename(tmp.getAbsolutePath(), f.getAbsolutePath());
                    committed = true;
                } catch (Exception e) {
                    committed = false;
                }
            }
        }
        if (!committed) {
            tmp.delete();
            return;
        }
        // 閲覧だけのセッションでも上限が効くよう、一定回数ごとに剪定する(ロック外)。
        if (writesSincePrune.incrementAndGet() >= 16) {
            writesSincePrune.set(0);
            pruneNow();
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

    static Bitmap generateForDisk(Photos.Item item, int px, boolean thumbnail, int priority, File file) {
        return decodeScaled(item, px, thumbnail, thumbnail ? 1.2f : 0f, priority,
                bitmap -> writeJpeg(file, bitmap));
    }

    private interface BitmapOutput {
        void write(Bitmap bitmap);
    }

    /** デコードからファイル出力まで、変換途中のビットマップも含めてRAMを予約する。 */
    private static Bitmap decodeScaled(Photos.Item it, int px, boolean byShortSide,
                                       float shrinkTol, int priority, BitmapOutput output) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.path, o);
            int base = byShortSide ? Math.min(o.outWidth, o.outHeight) : Math.max(o.outWidth, o.outHeight);
            if (base <= 0) return null;
            int s = 1;
            while (base / (s * 2) >= px) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            int copies = (it.rot != 0 || shrinkTol > 0) ? 2 : 1;
            long bytes = RamBudget.bitmapBytes(o.outWidth, o.outHeight, s, copies);
            if (!RamBudget.begin(bytes, priority)) return null;
            try {
                Bitmap b = BitmapFactory.decodeFile(it.path, o);
                if (b == null) return null;
                if (it.rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(it.rot);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
                if (shrinkTol > 0) {
                    Bitmap r = scaleShort(b, px, shrinkTol);
                    if (r != b) b.recycle();
                    b = r;
                }
                output.write(b);
                return b;
            } finally {
                RamBudget.end(bytes);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap decodeCached(File file, int priority) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        String path = file.getAbsolutePath();
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        long bytes = RamBudget.bitmapBytes(options.outWidth, options.outHeight, 1, 1);
        if (!RamBudget.begin(bytes, priority)) return null;
        try {
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        } finally {
            RamBudget.end(bytes);
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

    /** ディスクとメモリのキャッシュをすべて消す(裏書き込みと競合しない)。 */
    static void clearDisk() {
        synchronized (diskLock) {
            diskGeneration++;   // 圧縮中の書き込みをコミットさせない
            for (File f : allFiles(false)) f.delete();
        }
        synchronized (RamBudget.LOCK) {
            mem.clear();
            memBytes = 0;
            RamBudget.LOCK.notifyAll();
        }
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
    static void pruneNow() {
        synchronized (diskLock) {
            pruneInternal();
        }
    }

    /** diskLock 保持中にだけ呼ぶ。 */
    private static void pruneInternal() {
        try {
            deleteStaleTemp();
            File[] all = allFiles(true);   // 完成済み .jpg のみ(書き込み中の .tmp は触らない)
            if (all.length == 0) return;
            long limit = Prefs.limitMb(app) * 1024L * 1024L;
            long keep = limit / 5 * 4;
            long total = 0;
            for (File f : all) total += f.length();
            if (total <= limit) return;
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : all) {
                if (total <= keep) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Exception ignored) {
        }
    }
}

package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.view.WindowManager;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 全画面表示用のディスクキャッシュ。画面サイズに縮小した JPEG を
 * サムネイルとは別の full フォルダに保存する。メモリに全部載せずに先読みできる。
 */
final class ViewCache {
    private ViewCache() {}

    /** パス単位の single-flight。先読みと本表示が同じ画像を同時デコードしないようにする。 */
    private static final ConcurrentHashMap<String, Future<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private static final Set<String> ensureInFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    static synchronized void init(Context c) {
        // v1 削除と v2 移行は ThumbCache.init() 側のスレッドで一本化している。
        ThumbCache.init(c.getApplicationContext());
    }

    /** 全画面ではナビゲーションバーも隠す。縦横どちらでも同じキャッシュを使う。 */
    static int targetPx(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        return Math.max(size.x, size.y);
    }

    private static File fileFor(Photos.Item it, int px) {
        File d = ThumbCache.fullDir();
        if (d == null) return null;
        // v3 は元画像の更新日時を使わない。
        String key = it.path + "|" + it.size + "|v3|" + px;
        return new File(d, "v3" + ThumbCache.sha1(key) + ".jpg");
    }

    static boolean has(Photos.Item it, int px) {
        File f = fileFor(it, px);
        return f != null && f.exists();
    }

    /** ディスクにあれば読み込み、なければ作って保存する(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px, int priority) {
        File f = fileFor(it, px);
        if (f == null) return null;
        String key = f.getAbsolutePath();
        FutureTask<Bitmap> task = new FutureTask<>(() -> loadOrCreate(f, it, px, priority));
        Future<Bitmap> pending = inFlight.putIfAbsent(key, task);
        if (pending == null) {
            pending = task;
            try {
                task.run();
            } finally {
                inFlight.remove(key, task);
            }
        }
        try {
            return pending.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 同じパスの生成を1回だけ行う(single-flight の実体)。 */
    private static Bitmap loadOrCreate(File f, Photos.Item it, int px, int priority) {
        if (f.exists()) {
            Bitmap b = ThumbCache.decodeCached(f, px, false, priority);
            if (b != null) return b;
            if (Thread.currentThread().isInterrupted()) return null;
            f.delete();
        }
        return ThumbCache.generateForDisk(it, px, false, priority, f);
    }

    /** まだ無ければディスクに作る(メモリには残さない)。 */
    static void ensure(Photos.Item it, int px) {
        ensure(it, px, false);
    }

    /** 未作成のものだけ、または regenerateAll が true なら全件をディスクに作る。 */
    static void ensure(Photos.Item it, int px, boolean regenerateAll) {
        File f = fileFor(it, px);
        if (f == null || (!regenerateAll && f.exists())) return;
        if (!regenerateAll) {
            // 通常は get() と同じ single-flight を使う。返る Bitmap は共有なので recycle しない。
            get(it, px, RamBudget.GENERATING);
            return;
        }
        // 全件再生成だけは既存ファイルも上書きするため専用の排他を使う。
        String key = f.getAbsolutePath();
        if (!ensureInFlight.add(key)) return;
        try {
            Bitmap b = ThumbCache.generateForDisk(it, px, false, RamBudget.GENERATING, f);
            if (b != null) b.recycle();
        } finally {
            ensureInFlight.remove(key);
        }
    }

}

package com.example.photostand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
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
    private static final Set<String> ensureInFlight = ConcurrentHashMap.newKeySet();

    static synchronized void init(Context c) {
        // v1 削除と v2 移行は ThumbCache.init() 側のスレッドで一本化している。
        ThumbCache.init(c.getApplicationContext());
    }

    private static File fileFor(Photos.Item it, int px) {
        File d = ThumbCache.fullDir();
        if (d == null) return null;
        // v1 には画面長辺まで縮小した低解像度キャッシュが混在するため、v2 へ移行する。
        String key = it.path + "|" + it.size + "|" + it.modified + "|v2|" + px;
        return new File(d, "v2" + ThumbCache.sha1(key) + ".jpg");
    }

    static boolean has(Photos.Item it, int px) {
        File f = fileFor(it, px);
        return f != null && f.exists();
    }

    /** ディスクにあれば読み込み、なければ作って保存する(裏のスレッドから呼ぶこと)。 */
    static Bitmap get(Photos.Item it, int px) {
        File f = fileFor(it, px);
        if (f == null) return null;
        String key = f.getAbsolutePath();
        FutureTask<Bitmap> task = new FutureTask<>(() -> loadOrCreate(f, it, px));
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
    private static Bitmap loadOrCreate(File f, Photos.Item it, int px) {
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
            f.delete();
        }
        Bitmap b = generate(it, px);
        if (b != null) ThumbCache.writeJpeg(f, b);
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
        if (!regenerateAll) {
            // 通常は get() と同じ single-flight を使う。返る Bitmap は共有なので recycle しない。
            get(it, px);
            return;
        }
        // 全件再生成だけは既存ファイルも上書きするため専用の排他を使う。
        String key = f.getAbsolutePath();
        if (!ensureInFlight.add(key)) return;
        try {
            Bitmap b = generate(it, px);
            if (b == null) return;
            ThumbCache.writeJpeg(f, b);   // 自前生成なので recycle してよい
            b.recycle();
        } finally {
            ensureInFlight.remove(key);
        }
    }

    private static Bitmap generate(Photos.Item it, int px) {
        // 画面長辺への追加縮小は行わず、サンプリング後の解像度を保持して画質を優先する。
        return ThumbCache.decodeScaled(it, px, false, 0f);
    }

}

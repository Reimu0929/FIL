package com.example.photostand;

import android.content.ContentResolver;
import android.content.Context;
import android.util.DisplayMetrics;
import android.database.Cursor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全画面表示用ディスクキャッシュをまとめて作る処理。
 * 起動時に全フォルダ、またはフォルダを開いたときに、そのフォルダぶんを生成する。
 */
final class ViewPreloader {
    private ViewPreloader() {}

    static volatile boolean running;
    static volatile boolean cancel;
    static volatile int total;
    static final AtomicInteger done = new AtomicInteger();
    static volatile String message = "未実行";
    private static volatile long startMs;
    private static boolean startupStarted;
    /** 別ジョブ実行中に来たフォルダ要求を保留する。 */
    private static volatile String pendingBucket;

    private static int targetPx(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /** 起動時の自動生成。アプリを起動している間は1回だけ実行する。 */
    static synchronized void startAllOnce(Context ctx) {
        if (startupStarted || running) return;
        startupStarted = true;
        start(ctx, null, false);
    }

    /** 指定フォルダの未作成キャッシュを自動生成する。他ジョブ中なら完了後に実行する。 */
    static synchronized void startFolder(Context ctx, String bucket) {
        if (running) {
            pendingBucket = bucket;
            return;
        }
        pendingBucket = null;
        start(ctx, bucket, false);
    }

    /** 手動で全フォルダのキャッシュを生成する。 */
    static void startAll(Context ctx, boolean regenerateAll) {
        start(ctx, null, regenerateAll);
    }

    private static synchronized void start(Context ctx, String bucket, final boolean regenerateAll) {
        if (running) return;
        ViewCache.init(ctx.getApplicationContext());
        running = true;
        cancel = false;
        total = 0;
        done.set(0);
        startMs = System.currentTimeMillis();
        message = "写真の一覧を取得中";
        final ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        final int px = targetPx(ctx.getApplicationContext());
        new Thread(() -> {
            try (Cursor photos = Photos.query(cr, bucket)) {
                total = photos == null ? 0 : photos.getCount();
                message = regenerateAll ? "全画面用キャッシュを再生成中" : "未作成の全画面用キャッシュを生成中";
                Jobs.runWorkers(photos, done, () -> cancel,
                        it -> ViewCache.ensure(it, px, regenerateAll));
                message = cancel ? "停止しました" : "完了";
            } catch (Throwable t) {
                message = "エラーで中断しました";
            } finally {
                running = false;
                ThumbCache.pruneNow();
                String next;
                synchronized (ViewPreloader.class) {
                    next = pendingBucket;
                    pendingBucket = null;
                }
                if (next != null) start(ctx.getApplicationContext(), next, false);
            }
        }).start();
    }

    static void stop() {
        cancel = true;
        synchronized (ViewPreloader.class) {
            pendingBucket = null;
        }
    }

    static String status() {
        String pend;
        synchronized (ViewPreloader.class) {
            pend = pendingBucket;
        }
        String suffix = pend == null ? "" : " [保留: " + pend + "]";
        return Jobs.status(running, message, done.get(), total, startMs, "枚処理済み") + suffix;
    }
}

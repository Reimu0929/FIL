package com.example.photostand;

import android.content.ContentResolver;
import android.content.Context;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 全フォルダのサムネイル(大・中・小)をまとめて作る処理。画面を離れても続く。 */
final class CacheJob {
    private CacheJob() {}

    static volatile boolean running;
    static volatile boolean cancel;
    static volatile int total;
    static volatile String message = "未実行";
    static volatile long startMs;
    static final AtomicInteger done = new AtomicInteger();

    static synchronized void start(Context ctx, final boolean regenerateAll) {
        if (running) return;
        running = true;
        cancel = false;
        total = 0;
        done.set(0);
        startMs = System.currentTimeMillis();
        message = "写真の一覧を取得中";
        final ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        new Thread(() -> {
            try {
                final ArrayList<Photos.Item> all = Photos.loadAll(cr);
                total = all.size();
                message = regenerateAll ? "サムネイルを再生成中" : "未作成のサムネイルを生成中";
                Jobs.runWorkers(all, new AtomicInteger(), done, () -> cancel,
                        it -> ThumbCache.ensureAll(it, regenerateAll));
                message = cancel ? "停止しました" : "完了";
            } catch (Throwable t) {
                message = "エラーで中断しました";
            } finally {
                running = false;
                ThumbCache.pruneNow();
            }
        }).start();
    }

    static void stop() {
        cancel = true;
    }

    static String status() {
        return Jobs.status(running, message, done.get(), total, startMs, "枚");
    }
}

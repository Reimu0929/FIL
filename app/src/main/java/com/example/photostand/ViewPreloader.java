package com.example.photostand;

import android.content.ContentResolver;
import android.content.Context;
import android.util.DisplayMetrics;
import java.util.ArrayList;
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

    /** 指定フォルダの未作成キャッシュを自動生成する。 */
    static void startFolder(Context ctx, String bucket) {
        start(ctx, bucket, false);
    }

    /** 手動で全フォルダのキャッシュを生成する。 */
    static void startAll(Context ctx, boolean regenerateAll) {
        start(ctx, null, regenerateAll);
    }

    private static synchronized void start(Context ctx, String bucket, final boolean regenerateAll) {
        if (running) return;
        running = true;
        cancel = false;
        total = 0;
        done.set(0);
        startMs = System.currentTimeMillis();
        message = "写真の一覧を取得中";
        final ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        final int px = targetPx(ctx.getApplicationContext());
        new Thread(() -> {
            try {
                final ArrayList<Photos.Item> all = bucket == null
                        ? Photos.loadAll(cr)
                        : Photos.load(cr, bucket);
                total = all.size();
                message = regenerateAll ? "全画面用キャッシュを再生成中" : "未作成の全画面用キャッシュを生成中";
                final AtomicInteger next = new AtomicInteger();
                Thread[] ws = new Thread[2];
                for (int i = 0; i < ws.length; i++) {
                    ws[i] = new Thread(() -> {
                        for (;;) {
                            if (cancel) break;
                            int n = next.getAndIncrement();
                            if (n >= all.size()) break;
                            try {
                                ViewCache.ensure(all.get(n), px, regenerateAll);
                            } catch (Throwable ignored) {
                            }
                            done.incrementAndGet();
                        }
                    });
                    ws[i].start();
                }
                for (Thread w : ws) w.join();
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
        int t = total, d = done.get();
        if (running) return message + ": " + d + " / " + t + " 枚処理済み(経過 " + elapsed() + ")";
        if (t > 0) return message + ": " + d + " / " + t + " 枚処理済み";
        return message;
    }

    private static String elapsed() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}

package com.example.photostand;

import android.content.ContentResolver;
import android.content.Context;
import android.util.DisplayMetrics;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全画面表示用キャッシュをまとめて作る処理。
 * 起動時に全フォルダ、またはフォルダを開いたときに、そのフォルダぶんを先読みする。
 */
final class ViewPreloader {
    private ViewPreloader() {}

    static volatile boolean running;
    static volatile boolean cancel;
    static volatile int total;
    static volatile int done;
    static volatile String message = "未実行";
    private static volatile long startMs;
    private static boolean startupStarted;

    private static int targetPx(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /** 起動時の先読み。アプリを起動している間は1回だけ実行する。 */
    static synchronized void startAllOnce(Context ctx) {
        if (startupStarted || running) return;
        startupStarted = true;
        start(ctx, null);
    }

    /** 指定フォルダ(全画面に出す写真)の先読み。 */
    static void startFolder(Context ctx, String bucket) {
        start(ctx, bucket);
    }

    /** 手動で全フォルダの先読みを開始する。 */
    static void startAll(Context ctx) {
        start(ctx, null);
    }

    private static synchronized void start(Context ctx, String bucket) {
        if (running) return;
        running = true;
        cancel = false;
        total = 0;
        done = 0;
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
                message = "全画面用キャッシュを生成中";
                final AtomicInteger next = new AtomicInteger();
                Thread[] ws = new Thread[2];
                for (int i = 0; i < ws.length; i++) {
                    ws[i] = new Thread(() -> {
                        for (;;) {
                            if (cancel) break;
                            int n = next.getAndIncrement();
                            if (n >= all.size()) break;
                            try {
                                ViewCache.ensure(all.get(n), px);
                            } catch (Throwable ignored) {
                            }
                            done++;
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
        if (running) {
            return message + ": " + done + " / " + total + " 枚(経過 " + elapsed() + ")";
        }
        if (total > 0) return message + ": " + done + " / " + total + " 枚";
        return message;
    }

    private static String elapsed() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}

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

    static synchronized void start(Context ctx) {
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
                message = "生成中";
                final AtomicInteger next = new AtomicInteger();
                Thread[] ws = new Thread[2];
                for (int i = 0; i < ws.length; i++) {
                    ws[i] = new Thread(() -> {
                        for (;;) {
                            if (cancel) break;
                            int n = next.getAndIncrement();
                            if (n >= all.size()) break;
                            try {
                                ThumbCache.ensureAll(all.get(n));
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
        if (running) return message + ": " + d + " / " + t + " 枚(経過 " + elapsed() + ")";
        if (t > 0) return message + ": " + d + " / " + t + " 枚";
        return message;
    }

    private static String elapsed() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}

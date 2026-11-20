package com.example.photostand;

import android.database.Cursor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** CacheJob / ViewPreloader で共有する worker・経過時間・status の定型処理。 */
final class Jobs {
    private Jobs() {}

    interface Processor {
        void process(Photos.Item it);
    }

    /** java.util.function は API 24 以降なので、minSdk 21 対応のため独自定義する。 */
    interface Cancelled {
        boolean get();
    }

    /** 一覧を全件保持せず、2本のワーカーでカーソルから写真を1枚ずつ受け取る。 */
    static void runWorkers(Cursor photos, AtomicInteger done, Cancelled cancelled, Processor p) {
        if (photos == null) return;
        AtomicReference<RuntimeException> queryError = new AtomicReference<>();
        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                for (;;) {
                    Photos.Item item;
                    synchronized (photos) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted() || queryError.get() != null) return;
                        try {
                            if (!photos.moveToNext()) return;
                            item = Photos.read(photos);
                        } catch (RuntimeException e) {
                            queryError.set(e);
                            return;
                        }
                    }
                    if (item == null) continue;
                    try {
                        p.process(item);
                    } catch (Throwable ignored) {
                    }
                    done.incrementAndGet();
                }
            });
            workers[i].start();
        }
        boolean interrupted = false;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                    for (Thread active : workers) active.interrupt();
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        RuntimeException error = queryError.get();
        if (error != null) throw error;
    }

    static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    static String status(boolean running, String message, int done, int total, long startMs, String unit) {
        if (running) {
            // 一覧取得中は件数がまだ分からないので「0 / 0」を出さない。
            if (total == 0) return message + " (経過 " + elapsed(startMs) + ")";
            return message + ": " + done + " / " + total + " " + unit + "(経過 " + elapsed(startMs) + ")";
        }
        if (total > 0) return message + ": " + done + " / " + total + " " + unit;
        return message;
    }
}

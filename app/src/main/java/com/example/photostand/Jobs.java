package com.example.photostand;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 2本のワーカーで all を先頭から処理する。cancelled が真になったら途中で終了する。 */
    static void runWorkers(ArrayList<Photos.Item> all, AtomicInteger next, AtomicInteger done,
                           Cancelled cancelled, Processor p) {
        Thread[] ws = new Thread[2];
        for (int i = 0; i < ws.length; i++) {
            ws[i] = new Thread(() -> {
                for (;;) {
                    if (cancelled.get()) break;
                    int n = next.getAndIncrement();
                    if (n >= all.size()) break;
                    try {
                        p.process(all.get(n));
                    } catch (Throwable ignored) {
                    }
                    done.incrementAndGet();
                }
            });
            ws[i].start();
        }
        for (Thread w : ws) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

package com.example.photostand;

import android.content.Context;

/** アプリで管理できる参照と画像処理のRAM予算。Androidのプロセス上限とは別に扱う。 */
final class RamBudget {
    static final Object LOCK = new Object();
    static final int CURRENT = 0;
    static final int PREFETCH = 1;
    static final int GENERATING = 2;
    static final int CURRENT_THUMB = 3;
    static final int PHOTO_LIST = 4;
    static final int OTHER_THUMB = 5;

    private static long limit = Runtime.getRuntime().maxMemory() / 2L;
    private static long photoLists;
    private static long workingBytes;
    private static final int[] waiting = new int[OTHER_THUMB + 1];
    private static ViewerActivity viewer;

    private RamBudget() {}

    static void configure(Context context) {
        long bytes = Prefs.ramLimitBytes(context);
        synchronized (LOCK) {
            limit = bytes;
            makeRoom(0, CURRENT);
            LOCK.notifyAll();
        }
    }

    static long limit() {
        synchronized (LOCK) { return limit; }
    }

    static void attachViewer(ViewerActivity activity) {
        synchronized (LOCK) { viewer = activity; }
    }

    static void detachViewer(ViewerActivity activity) {
        synchronized (LOCK) {
            if (viewer == activity) viewer = null;
            LOCK.notifyAll();
        }
    }

    static void replaceListBytes(long before, long after) {
        synchronized (LOCK) {
            photoLists += after - before;
            makeRoom(0, PHOTO_LIST);
            LOCK.notifyAll();
        }
    }

    /** LOCK 保持中に呼ぶ。低い優先順位のキャッシュから必要なぶんだけ外す。 */
    static boolean makeRoom(long bytes, int priority) {
        long excess = usedBytes() + bytes - limit;
        if (excess > 0 && priority < OTHER_THUMB) excess -= ThumbCache.trimRam(false, excess);
        if (excess > 0 && priority < CURRENT_THUMB) excess -= ThumbCache.trimRam(true, excess);
        if (excess > 0 && priority < PREFETCH && viewer != null) {
            excess -= viewer.trimPrefetchRam(excess);
        }
        return excess <= 0;
    }

    private static long usedBytes() {
        return photoLists + workingBytes + ThumbCache.memBytes()
                + (viewer == null ? 0 : viewer.retainedRamBytes());
    }

    static long bitmapBytes(int width, int height, int sample, int copies) {
        long w = ((long) width + sample - 1L) / sample;
        long h = ((long) height + sample - 1L) / sample;
        return Math.min(Integer.MAX_VALUE, w * h) * 4L * copies;
    }

    static boolean begin(long bytes, int priority) {
        synchronized (LOCK) {
            waiting[priority]++;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    boolean higherWaiting = false;
                    for (int i = 0; i < priority; i++) {
                        if (waiting[i] != 0) { higherWaiting = true; break; }
                    }
                    // 一枚ずつなら、必須データだけで予算を超える場合も処理を進める。
                    if (!higherWaiting && (makeRoom(bytes, priority) || workingBytes == 0)) {
                        workingBytes += bytes;
                        return true;
                    }
                    LOCK.wait();
                }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                waiting[priority]--;
                LOCK.notifyAll();
            }
        }
    }

    static void end(long bytes) {
        synchronized (LOCK) {
            workingBytes -= bytes;
            LOCK.notifyAll();
        }
    }
}

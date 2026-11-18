package com.example.photostand;

import android.content.Context;
import android.content.SharedPreferences;

/** アプリの設定値(設定画面で変更できる)。 */
final class Prefs {
    private Prefs() {}

    /** 全画面の先読み枚数の上限(左右それぞれ)。 */
    static final int PRELOAD_MAX = 100;
    /** キャッシュサイズの上限(MB)。 */
    static final int CACHE_MAX_MB = 10000;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ps", Context.MODE_PRIVATE);
    }

    /** サムネイルキャッシュの上限(MB) */
    static int limitMb(Context c) { return sp(c).getInt("limitMb", 300); }

    /** 全画面表示で先読みする枚数(左右それぞれ) */
    static int preload(Context c) {
        int n = sp(c).getInt("preload", 5);
        return Math.max(0, Math.min(n, PRELOAD_MAX));
    }

    /** タイル画面で先読みする量(ページ数) */
    static float pages(Context c) { return sp(c).getFloat("pages", 1f); }

    /** 読み込み済みの枚数を画面に出すか */
    static boolean showCount(Context c) { return sp(c).getBoolean("showCount", false); }

    /**
     * 全画面用キャッシュの一括先読み。
     * 0 = オフ、1 = 起動時に全フォルダ、2 = フォルダを開いたとき
     */
    static int preloadAll(Context c) {
        int m = sp(c).getInt("preloadAll", 0);
        return Math.max(0, Math.min(m, 2));
    }

    static void setPreloadAll(Context c, int mode) {
        sp(c).edit().putInt("preloadAll", mode).apply();
    }
}

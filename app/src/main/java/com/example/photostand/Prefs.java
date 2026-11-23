package com.example.photostand;

import android.content.Context;
import android.content.SharedPreferences;

/** アプリの設定値(設定画面で変更できる)。 */
final class Prefs {
    private Prefs() {}

    /** 全画面の先読み枚数の上限(左右それぞれ)。 */
    static final int PRELOAD_MAX = 100;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ps", Context.MODE_PRIVATE);
    }

    /** サムネイルキャッシュの上限(MB) */
    static int limitMb(Context c) { return sp(c).getInt("limitMb", 300); }

    /**
     * 全画面キャッシュのRAM上限(MB)。0 は「自動(maxMemory/2)」だったが、
     * largeHeap だと端末の空きと無関係に大きくなるため、2GB端末向けに 96MB を既定とする。
     */
    static int ramOverrideMb(Context c) { return sp(c).getInt("ramOverrideMb", 96); }

    static int maxRamOverrideMb() {
        return (int) Math.min(Integer.MAX_VALUE >> 20, Runtime.getRuntime().maxMemory() >> 20);
    }

    static long ramLimitBytes(Context c) {
        int mb = ramOverrideMb(c);
        return mb > 0 ? (long) Math.min(mb, maxRamOverrideMb()) << 20
                : Runtime.getRuntime().maxMemory() / 2L;
    }

    static boolean keepZoomBitmap(Context c) {
        return sp(c).getBoolean("keepZoomBitmap", true);
    }

    /** 全画面表示で先読みする枚数(左右それぞれ) */
    static int preload(Context c) {
        int n = sp(c).getInt("preload", 5);
        return Math.max(0, Math.min(n, PRELOAD_MAX));
    }

    /** タイル画面で先読みする量(ページ数) */
    static float pages(Context c) { return sp(c).getFloat("pages", 1f); }

    /** メモリ内キャッシュの枚数とRAM使用量を画面に出すか */
    static boolean showCount(Context c) { return sp(c).getBoolean("showCount", false); }

    /** 手動生成で、キャッシュ済みの写真も作り直すか。オフなら未作成のものだけを作る。 */
    static boolean regenerateAll(Context c) { return sp(c).getBoolean("regenerateAll", false); }

    /**
     * 全画面用ディスクキャッシュの自動生成。
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

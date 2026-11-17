package com.example.photostand;

import android.content.Context;
import android.content.SharedPreferences;

/** アプリの設定値(設定画面で変更できる)。 */
final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ps", Context.MODE_PRIVATE);
    }

    /** サムネイルキャッシュの上限(MB) */
    static int limitMb(Context c) { return sp(c).getInt("limitMb", 300); }

    /** 全画面表示で先読みする枚数(左右それぞれ) */
    static int preload(Context c) { return sp(c).getInt("preload", 2); }

    /** タイル画面で先読みする量(ページ数) */
    static float pages(Context c) { return sp(c).getFloat("pages", 1f); }

    /** 読み込み済みの枚数を画面に出すか */
    static boolean showCount(Context c) { return sp(c).getBoolean("showCount", false); }
}

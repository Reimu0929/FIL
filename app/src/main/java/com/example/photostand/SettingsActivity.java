package com.example.photostand;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 設定画面。キャッシュの管理と、先読みなどの調整。 */
public class SettingsActivity extends Activity {
    private static final int[] LIMITS = {100, 300, 500, 1000, 2000};
    private static final String[] LIMIT_LABELS = {"100MB", "300MB", "500MB", "1GB", "2GB"};
    private static final int[] PRELOADS = {0, 1, 2, 3, 5, 10};
    private static final String[] PRELOAD_LABELS = {"なし", "左右1枚", "左右2枚", "左右3枚", "左右5枚", "左右10枚"};
    private static final float[] PAGES = {0f, 0.5f, 1f, 2f, 2.5f, 3f, 5f};
    private static final String[] PAGE_LABELS = {"なし", "半ページ分", "1ページ分", "2ページ分", "2ページ半", "3ページ分", "5ページ分"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView cacheInfo, jobStatus, limitV, preloadV, pagesV;
    private Button jobBtn;
    private int pollCount;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            jobStatus.setText(CacheJob.status());
            jobBtn.setText(CacheJob.running ? "生成を停止" : "全キャッシュを生成(大・中・小 / 全フォルダ)");
            if (CacheJob.running) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (++pollCount % 6 == 0) refreshInfo();
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (pollCount != 0) {
                    pollCount = 0;
                    refreshInfo();
                }
            }
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("設定");
        ThumbCache.init(getApplicationContext());

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));
        sv.addView(root);

        section(root, "サムネイルのキャッシュ");
        cacheInfo = new TextView(this);
        cacheInfo.setTextSize(15);
        cacheInfo.setPadding(0, dp(8), 0, dp(8));
        root.addView(cacheInfo);

        jobBtn = new Button(this);
        jobBtn.setOnClickListener(v -> {
            if (CacheJob.running) CacheJob.stop();
            else CacheJob.start(this);
        });
        root.addView(jobBtn);
        jobStatus = new TextView(this);
        jobStatus.setTextSize(14);
        jobStatus.setTextColor(Color.GRAY);
        jobStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(jobStatus);

        Button clear = new Button(this);
        clear.setText("キャッシュをすべて削除");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("保存したサムネイルをすべて削除します。写真は消えません。")
                .setPositiveButton("削除", (d, w) -> new Thread(() -> {
                    ThumbCache.clearDisk();
                    h.post(this::refreshInfo);
                }).start())
                .setNegativeButton("キャンセル", null).show());
        root.addView(clear);

        limitV = row(root, "キャッシュサイズの上限", v -> choose("キャッシュサイズの上限", LIMIT_LABELS,
                indexOf(LIMITS, Prefs.limitMb(this)), i -> {
                    Prefs.sp(this).edit().putInt("limitMb", LIMITS[i]).apply();
                    showValues();
                    new Thread(() -> {
                        ThumbCache.pruneNow();
                        h.post(this::refreshInfo);
                    }).start();
                }));

        section(root, "表示と先読み");
        preloadV = row(root, "全画面の先読み枚数", v -> choose("全画面の先読み枚数", PRELOAD_LABELS,
                indexOf(PRELOADS, Prefs.preload(this)), i -> {
                    Prefs.sp(this).edit().putInt("preload", PRELOADS[i]).apply();
                    showValues();
                }));
        pagesV = row(root, "タイル画面の先読み量", v -> choose("タイル画面の先読み量", PAGE_LABELS,
                indexOf(PAGES, Prefs.pages(this)), i -> {
                    Prefs.sp(this).edit().putFloat("pages", PAGES[i]).apply();
                    showValues();
                }));

        Switch sw = new Switch(this);
        sw.setText("読み込み済みの枚数を画面に表示");
        sw.setTextSize(18);
        sw.setPadding(0, dp(16), 0, dp(16));
        sw.setChecked(Prefs.showCount(this));
        sw.setOnCheckedChangeListener((b, on) -> Prefs.sp(this).edit().putBoolean("showCount", on).apply());
        root.addView(sw);

        setContentView(sv);
        showValues();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshInfo();
        h.post(poll);
    }

    @Override protected void onPause() {
        h.removeCallbacks(poll);
        super.onPause();
    }

    private void showValues() {
        limitV.setText(LIMIT_LABELS[indexOf(LIMITS, Prefs.limitMb(this))] + "(超えたら古いものから削除)");
        preloadV.setText(PRELOAD_LABELS[indexOf(PRELOADS, Prefs.preload(this))] + "(増やすほどメモリを使います)");
        pagesV.setText(PAGE_LABELS[indexOf(PAGES, Prefs.pages(this))] + "(画面の外のサムネイルを先に作ります)");
    }

    /** キャッシュフォルダの大きさを数えて表示する(裏のスレッドで数える)。 */
    private void refreshInfo() {
        new Thread(() -> {
            long[] st = ThumbCache.diskStats();
            String text = "保存先: " + ThumbCache.where
                    + "\nサイズ: " + String.format("%.1f MB", st[0] / 1048576.0)
                    + "(" + st[1] + " ファイル)";
            h.post(() -> cacheInfo.setText(text));
        }).start();
    }

    private void section(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(0xFFE8C26A);
        t.setPadding(0, dp(20), 0, dp(4));
        parent.addView(t);
    }

    private TextView row(LinearLayout parent, String title, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        TextView v = new TextView(this);
        v.setTextSize(14);
        v.setTextColor(Color.GRAY);
        r.addView(t);
        r.addView(v);
        r.setOnClickListener(l);
        parent.addView(r);
        return v;
    }

    private interface IntCb { void run(int i); }

    private void choose(String title, String[] labels, int checked, IntCb cb) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    cb.run(which);
                    d.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private static int indexOf(float[] a, float v) {
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - v) < 0.01f) return i;
        return 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

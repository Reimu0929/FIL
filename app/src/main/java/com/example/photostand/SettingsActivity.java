package com.example.photostand;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Debug;
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
    private static final int[] LIMITS = {100, 300, 500, 1000, 2000, 5000, 10000};
    private static final String[] LIMIT_LABELS = {"100MB", "300MB", "500MB", "1GB", "2GB", "5GB", "10GB"};
    private static final int[] PRELOADS = {0, 1, 2, 3, 5, 10, 20, 30, 50, 100};
    private static final String[] PRELOAD_LABELS = {
            "なし", "左右1枚", "左右2枚", "左右3枚", "左右5枚",
            "左右10枚", "左右20枚", "左右30枚", "左右50枚", "左右100枚"};
    private static final int[] PRELOAD_ALL = {0, 1, 2};
    private static final String[] PRELOAD_ALL_LABELS = {
            "自動生成しない(表示時に必要なものだけ作成)",
            "アプリ起動時に未作成分を生成(全フォルダ)",
            "フォルダを開いたときに未作成分を生成(そのフォルダ)"};
    private static final float[] PAGES = {0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 5f};
    private static final String[] PAGE_LABELS = {
            "なし", "半ページ分", "1ページ分", "1.5ページ分",
            "2ページ分", "2ページ半", "3ページ分", "5ページ分"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView cacheInfo, memoryInfo, jobStatus, preloadStatus;
    private TextView limitV, preloadV, pagesV, preloadAllV;
    private Button jobBtn, preloadBtn;
    private Switch regenerate;
    private int pollCount, memoryTick;
    private volatile boolean infoRunning;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            updateJobButtons();
            jobStatus.setText(CacheJob.status());
            preloadStatus.setText(ViewPreloader.status());
            if (++memoryTick % 4 == 0) updateMemoryInfo();
            boolean busy = CacheJob.running || ViewPreloader.running;
            if (busy) {
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
        ViewCache.init(getApplicationContext());

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), dp(24));
        sv.addView(root);

        section(root, "ディスクキャッシュの状況");
        cacheInfo = new TextView(this);
        cacheInfo.setTextSize(15);
        cacheInfo.setPadding(0, dp(8), 0, dp(8));
        root.addView(cacheInfo);

        section(root, "RAM使用量");
        memoryInfo = new TextView(this);
        memoryInfo.setTextSize(15);
        memoryInfo.setPadding(0, dp(8), 0, dp(8));
        root.addView(memoryInfo);

        limitV = row(root, "ディスクキャッシュの上限(合計)", v -> choose("ディスクキャッシュの上限(合計)", LIMIT_LABELS,
                indexOf(LIMITS, Prefs.limitMb(this)), i -> {
                    Prefs.sp(this).edit().putInt("limitMb", LIMITS[i]).apply();
                    showValues();
                    new Thread(() -> {
                        ThumbCache.pruneNow();
                        h.post(this::refreshInfo);
                    }).start();
                }));

        Button clear = new Button(this);
        clear.setText("ディスクキャッシュをすべて削除");
        clear.setOnClickListener(v -> {
            if (CacheJob.running || ViewPreloader.running) {
                new AlertDialog.Builder(this)
                        .setMessage("キャッシュ生成を停止してから削除してください。")
                        .setPositiveButton("OK", null).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setMessage("保存したサムネイルと全画面用キャッシュをすべて削除します。写真は消えません。")
                    .setPositiveButton("削除", (d, w) -> new Thread(() -> {
                        ThumbCache.clearDisk();
                        h.post(this::refreshInfo);
                    }).start())
                    .setNegativeButton("キャンセル", null).show();
        });
        root.addView(clear);

        section(root, "手動でキャッシュを生成");
        regenerate = new Switch(this);
        regenerate.setText("キャッシュ済みの写真も再生成する");
        regenerate.setTextSize(18);
        regenerate.setPadding(0, dp(12), 0, dp(4));
        regenerate.setChecked(Prefs.regenerateAll(this));
        regenerate.setOnCheckedChangeListener((b, on) -> {
            Prefs.sp(this).edit().putBoolean("regenerateAll", on).apply();
            updateJobButtons();
        });
        root.addView(regenerate);
        TextView generationNote = new TextView(this);
        generationNote.setText("オフ: 未作成のキャッシュだけを生成\nオン: キャッシュ済みも含めて全件再生成");
        generationNote.setTextSize(14);
        generationNote.setTextColor(Color.GRAY);
        generationNote.setPadding(0, 0, 0, dp(8));
        root.addView(generationNote);

        jobBtn = new Button(this);
        jobBtn.setOnClickListener(v -> {
            if (CacheJob.running) CacheJob.stop();
            else CacheJob.start(this, Prefs.regenerateAll(this));
        });
        root.addView(jobBtn);
        jobStatus = new TextView(this);
        jobStatus.setTextSize(14);
        jobStatus.setTextColor(Color.GRAY);
        jobStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(jobStatus);

        section(root, "全画面用ディスクキャッシュ");
        preloadAllV = row(root, "自動生成のタイミング", v -> choose("自動生成のタイミング", PRELOAD_ALL_LABELS,
                indexOf(PRELOAD_ALL, Prefs.preloadAll(this)), i -> {
                    Prefs.setPreloadAll(this, PRELOAD_ALL[i]);
                    showValues();
                }));

        preloadBtn = new Button(this);
        preloadBtn.setOnClickListener(v -> {
            if (ViewPreloader.running) ViewPreloader.stop();
            else ViewPreloader.startAll(this, Prefs.regenerateAll(this));
        });
        root.addView(preloadBtn);
        preloadStatus = new TextView(this);
        preloadStatus.setTextSize(14);
        preloadStatus.setTextColor(Color.GRAY);
        preloadStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(preloadStatus);

        section(root, "表示時の先読み(ディスク＋RAM)");
        preloadV = row(root, "全画面で先読みする前後の枚数", v -> choose("全画面で先読みする前後の枚数", PRELOAD_LABELS,
                indexOf(PRELOADS, Prefs.preload(this)), i -> {
                    Prefs.sp(this).edit().putInt("preload", PRELOADS[i]).apply();
                    showValues();
                }));
        pagesV = row(root, "タイルで先読みする画面外の範囲", v -> choose("タイルで先読みする画面外の範囲", PAGE_LABELS,
                indexOf(PAGES, Prefs.pages(this)), i -> {
                    Prefs.sp(this).edit().putFloat("pages", PAGES[i]).apply();
                    showValues();
                }));

        Switch sw = new Switch(this);
        sw.setText("キャッシュ枚数とRAM使用量を写真画面に表示");
        sw.setTextSize(18);
        sw.setPadding(0, dp(16), 0, dp(16));
        sw.setChecked(Prefs.showCount(this));
        sw.setOnCheckedChangeListener((b, on) -> Prefs.sp(this).edit().putBoolean("showCount", on).apply());
        root.addView(sw);

        TextView version = new TextView(this);
        version.setTextSize(12);
        version.setTextColor(Color.GRAY);
        version.setPadding(0, dp(24), 0, 0);
        version.setText("PhotoViewer  バージョン " + versionName());
        root.addView(version);

        setContentView(sv);
        showValues();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshInfo();
        updateMemoryInfo();
        h.post(poll);
    }

    @Override protected void onPause() {
        h.removeCallbacks(poll);
        super.onPause();
    }

    private void updateJobButtons() {
        if (jobBtn == null || preloadBtn == null || regenerate == null) return;
        boolean all = regenerate.isChecked();
        jobBtn.setText(CacheJob.running ? "生成を停止"
                : all ? "サムネイルを全件再生成(全フォルダ)" : "未作成のサムネイルを生成(全フォルダ)");
        preloadBtn.setText(ViewPreloader.running ? "生成を停止"
                : all ? "全画面用キャッシュを全件再生成(全フォルダ)" : "未作成の全画面用キャッシュを生成(全フォルダ)");
    }

    private void showValues() {
        limitV.setText(LIMIT_LABELS[indexOf(LIMITS, Prefs.limitMb(this))] + "(超えたら古いものから削除)");
        preloadV.setText(PRELOAD_LABELS[indexOf(PRELOADS, Prefs.preload(this))]
                + "(左右それぞれ最大 " + Prefs.PRELOAD_MAX + " 枚、合計最大 " + (Prefs.PRELOAD_MAX * 2)
                + " 枚。ディスクへ準備し、RAMには上限内で保持します)");
        pagesV.setText(PAGE_LABELS[indexOf(PAGES, Prefs.pages(this))] + "(画面の外のサムネイルを先に作ります)");
        preloadAllV.setText(PRELOAD_ALL_LABELS[indexOf(PRELOAD_ALL, Prefs.preloadAll(this))]);
    }

    /** キャッシュフォルダの大きさと種類別のファイル数を裏のスレッドで数える。 */
    private void refreshInfo() {
        if (infoRunning) return;
        infoRunning = true;
        new Thread(() -> {
            try {
                ThumbCache.DiskStats st = ThumbCache.diskStats();
                String text = "保存先: " + ThumbCache.where
                        + "\nサムネイル: " + st.thumbFiles + " ファイル / " + mb(st.thumbBytes)
                        + "\n  (1枚の写真につき小・中・大の最大3ファイル)"
                        + "\n全画面用: " + st.viewFiles + " ファイル / " + mb(st.viewBytes)
                        + "\n合計: " + st.totalFiles() + " ファイル / " + mb(st.totalBytes());
                h.post(() -> cacheInfo.setText(text));
            } finally {
                infoRunning = false;
            }
        }).start();
    }

    private void updateMemoryInfo() {
        if (memoryInfo == null) return;
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        long appBytes = mi.getTotalPss() * 1024L;
        memoryInfo.setText("アプリ全体(概算・全画面用も含む): " + mb(appBytes)
                + "\nうちサムネイルのRAMキャッシュ: " + ThumbCache.memCount() + " 枚 / "
                + mb(ThumbCache.memBytes()));
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
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

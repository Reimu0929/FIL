package com.example.photostand;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** フォルダ一覧。タップするとそのフォルダの写真をタイルで一覧表示する。右上の3ドットから設定へ。 */
public class MainActivity extends Activity {
    private final ArrayList<String> ids = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView empty;
    private boolean asked;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                t.setTextSize(20);
                t.setPadding(dp(20), dp(16), dp(20), dp(16));
                return t;
            }
        };
        list.setAdapter(adapter);
        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(16);
        empty.setText("写真が見つかりません");
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        setContentView(body);
        setTitle("フォルダ");

        list.setOnItemClickListener((parent, view, pos, id) -> {
            Intent i = new Intent(this, GridActivity.class);
            i.putExtra("bucket", ids.get(pos));
            i.putExtra("name", names.get(pos));
            startActivity(i);
        });
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "設定").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() {
        super.onResume();
        if (granted()) {
            load();
            maybeStartupPreload();
        } else if (!asked) {
            asked = true;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (granted()) {
            load();
            maybeStartupPreload();
        } else {
            empty.setText("写真へのアクセスを許可してください");
        }
    }

    /** 設定が「アプリ起動時」なら、起動後に一度だけ全画面用ディスクキャッシュを作る。 */
    private void maybeStartupPreload() {
        if (Prefs.preloadAll(this) == 1) ViewPreloader.startAllOnce(this);
    }

    private boolean granted() {
        return Build.VERSION.SDK_INT < 23
            || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void load() {
        ids.clear(); names.clear(); labels.clear();
        LinkedHashMap<String, Integer> count = new LinkedHashMap<>();
        LinkedHashMap<String, String> nm = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME},
                null, null, MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    if (id == null) continue;
                    Integer n = count.get(id);
                    count.put(id, n == null ? 1 : n + 1);
                    if (!nm.containsKey(id)) {
                        String name = c.getString(1);
                        nm.put(id, name == null ? "(名称なし)" : name);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (Map.Entry<String, Integer> en : count.entrySet()) {
            String name = nm.get(en.getKey());
            ids.add(en.getKey());
            names.add(name);
            labels.add(name + "  (" + en.getValue() + ")");
        }
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

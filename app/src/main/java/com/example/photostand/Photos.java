package com.example.photostand;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.Collections;

/** フォルダ内の写真をファイル名の昇順で読み込む(大文字小文字は区別せず、数字は大きさで比べる)。 */
final class Photos {
    private Photos() {}

    static final class Item {
        final String name, path;
        final int rot;
        final long size, modified;
        Item(String name, String path, int rot, long size, long modified) {
            this.name = name;
            this.path = path;
            this.rot = rot;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final String[] COLS = {
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED};

    private static void fill(ContentResolver cr, String selection, String[] args, ArrayList<Item> out) {
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, COLS, selection, args, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String path = c.getString(1);
                    if (path == null) continue;
                    String name = c.getString(0);
                    out.add(new Item(name == null ? path : name, path, c.getInt(2), c.getLong(3), c.getLong(4)));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 全フォルダの写真(並べ替えなし)。キャッシュの一括生成用。 */
    static ArrayList<Item> loadAll(ContentResolver cr) {
        ArrayList<Item> out = new ArrayList<>();
        fill(cr, null, null, out);
        return out;
    }

    /** 1つのフォルダの写真をファイル名順で返す。 */
    static ArrayList<Item> load(ContentResolver cr, String bucket) {
        ArrayList<Item> out = new ArrayList<>();
        fill(cr, MediaStore.Images.Media.BUCKET_ID + "=?", new String[]{bucket}, out);
        Collections.sort(out, (a, b) -> {
            int r = natural(a.name, b.name);
            if (r == 0) r = a.name.compareTo(b.name);
            return r != 0 ? r : a.path.compareTo(b.path);
        });
        return out;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /** 自然順の比較: 「img2」は「img10」より前。数字の連続は大きさで、それ以外は大文字小文字を無視して比べる。 */
    static int natural(String a, String b) {
        int i = 0, j = 0, la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (digit(ca) && digit(cb)) {
                // 部分文字列も正規表現も作らず、桁数→辞書順で比較する。
                // 先頭のゼロは読み飛ばすので "img0002" と "img2" は同じ数値として扱われる。
                int si = i, sj = j;
                while (si < la && a.charAt(si) == '0') si++;
                while (sj < lb && b.charAt(sj) == '0') sj++;
                int di = si, dj = sj;
                while (i < la && digit(a.charAt(i))) i++;
                while (j < lb && digit(b.charAt(j))) j++;
                int lenA = i - di, lenB = j - dj;
                if (lenA != lenB) return lenA < lenB ? -1 : 1;
                for (int p = 0; p < lenA; p++) {
                    char x = a.charAt(di + p), y = b.charAt(dj + p);
                    if (x != y) return x < y ? -1 : 1;
                }
            } else {
                int r = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (r != 0) return r;
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }
}

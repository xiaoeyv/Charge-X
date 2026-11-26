package com.xiaoeyv.chargex;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** CSV 落盘。API 29+ 直接写 Downloads（免任何权限），低版本由调用方走 SAF 提供 Uri。 */
public final class CsvExporter {

    public static final String MIME = "text/csv";
    public static final String SUB_DIR = "Charge-X";

    private CsvExporter() {
    }

    public static String newFileName() {
        return CsvFormat.newFileName();
    }

    /** API 29+：写入 下载/Charge-X/xxx.csv，返回可展示的路径 */
    public static String exportToDownloads(Context ctx, String csv) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("系统版本过低，请使用手动选择路径导出");
        }
        String name = newFileName();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("无法在下载目录创建文件");
        write(ctx, uri, csv);
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR + "/" + name;
    }

    public static void write(Context ctx, Uri uri, String csv) throws IOException {
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new IOException("打开目标文件失败");
            os.write(csv.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}

package com.localreader.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;

/** Test APK component uses Java only: the target APK owns the instrumented Kotlin runtime. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = Objects.requireNonNull(uri.getLastPathSegment());
        if (name.contains("/") || name.contains("..")) throw new IllegalArgumentException("Invalid fixture name");
        File root = Objects.requireNonNull(getContext()).getFilesDir();
        root.mkdirs();
        return new File(root, name);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int flags;
        if ("r".equals(mode)) flags = ParcelFileDescriptor.MODE_READ_ONLY;
        else if ("w".equals(mode) || "wt".equals(mode)) flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        else throw new IllegalArgumentException("Unsupported fixture mode");
        return ParcelFileDescriptor.open(file(uri), flags);
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File source = file(uri);
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = source.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = source.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns); cursor.addRow(values); return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return file(uri).delete() ? 1 : 0; }
}

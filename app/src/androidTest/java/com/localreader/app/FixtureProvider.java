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
    private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> started = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> reads = new java.util.concurrent.ConcurrentHashMap<>();
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
        if ("r".equals(mode)) { flags = ParcelFileDescriptor.MODE_READ_ONLY;reads.computeIfAbsent(file(uri).getName(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet(); }
        else if ("w".equals(mode) || "wt".equals(mode)) flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        else throw new IllegalArgumentException("Unsupported fixture mode");
        return ParcelFileDescriptor.open(file(uri), flags);
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return query(uri,projection,selection,selectionArgs,sortOrder,null);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, android.os.CancellationSignal cancellation) {
        File source = file(uri);
        String behavior=uri.getQueryParameter("metadata");
        if ("fail".equals(behavior)) throw new IllegalStateException("Owned metadata failure");
        if ("block".equals(behavior)) {
            started.computeIfAbsent(source.getName(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            long deadline=android.os.SystemClock.uptimeMillis()+10000;
            while (android.os.SystemClock.uptimeMillis()<deadline) {
                if (cancellation!=null) cancellation.throwIfCanceled();
                android.os.SystemClock.sleep(10);
            }
            throw new IllegalStateException("Owned metadata cancellation was not delivered");
        }
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = source.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = source.length();
            else if ("started".equals(columns[i])) values[i] = started.getOrDefault(source.getName(),new java.util.concurrent.atomic.AtomicInteger()).get();
            else if ("reads".equals(columns[i])) values[i] = reads.getOrDefault(source.getName(),new java.util.concurrent.atomic.AtomicInteger()).get();
        }
        MatrixCursor cursor = new MatrixCursor(columns); cursor.addRow(values); return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return file(uri).delete() ? 1 : 0; }
}

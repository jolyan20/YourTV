package sa.urtv.screensaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only content URI for the Android package installer, without external storage access. */
public final class UpdateApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri == null || uri.getPathSegments().size() != 1)
            throw new FileNotFoundException("Read-only APK access");
        String name = uri.getLastPathSegment();
        if (!"urtv-home.apk".equals(name) && !"urtv-screensaver.apk".equals(name))
            throw new FileNotFoundException("Invalid APK name");
        File folder = new File(getContext().getCacheDir(), "apk-updates");
        File file = new File(folder, name);
        if (!file.isFile()) throw new FileNotFoundException("APK not downloaded");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}

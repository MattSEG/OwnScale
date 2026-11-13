package org.ownscale.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;

public class ShareProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  File file(Uri uri) throws FileNotFoundException {
    if (!uri.getPath().equals("/ownscale.csv")) throw new FileNotFoundException();
    return new File(getContext().getCacheDir(), "ownscale.csv");
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!mode.equals("r")) throw new FileNotFoundException("Read-only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    return "text/csv";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = file(uri);
      MatrixCursor c =
          new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
      c.addRow(new Object[] {f.getName(), f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}

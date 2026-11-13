package org.ownscale.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import org.json.JSONObject;

/** All numbers, IDs and dates in this fixture are deliberately synthetic. */
public class AppVerification extends Instrumentation {
  void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  void add(
      Store s,
      String id,
      String time,
      String source,
      String session,
      String metric,
      double value,
      String metadata) {
    s.db.execSQL(
        "INSERT INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
        new Object[] {
          id,
          time,
          source,
          session,
          metric,
          value,
          metric.equals("weight") ? "kg" : "percent",
          "synthetic-fixture",
          metadata,
          time
        });
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      MainActivity a =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      .putExtra("test_mode", true));
      final Throwable[] error = {null};
      runOnMainSync(
          () -> {
            try {
              Store s = new Store(SQLiteDatabase.create(null));
              String meta = "{\"unadjusted_model_percent\":25,\"height_cm\":175,\"sex_code\":1}";
              String t = "2024-02-01T08:00:00Z";
              add(s, "ref-w", t, "hume_ble", "reference", "weight", 80, "{}");
              add(s, "ref-f", t, "ownscale_experimental", "reference", "body_fat", 25, meta);
              s.db.execSQL(
                  "INSERT INTO model_baselines VALUES(?,?,?,?,?,?,?,?)",
                  new Object[] {
                    "fixture", "reference", 20, 25, 175, 1, t, "Synthetic test anchor"
                  });
              for (int i = 0; i < 8; i++) {
                String time =
                    Instant.parse("2024-01-01T08:00:00Z").plusSeconds(i * 86400L * 3).toString();
                add(s, "hw" + i, time, "hume", "legacy" + i, "weight", 90 - i, "{}");
                add(s, "hf" + i, time, "hume", "legacy" + i, "body_fat", 12 + i * .2, "{}");
              }
              s.seedCalibration();
              s.set("bf_response_hours", "0");
              Store.Reading h =
                  s.readings().stream()
                      .filter(r -> r.weight != null && r.weight == 90)
                      .findFirst()
                      .get();
              check(Math.abs(h.fat - 100 * 23.5 / 90) < 1e-9, "Reconstruction");
              check(h.originalFat == 12, "Original preservation");
              Store restored = new Store(SQLiteDatabase.create(null));
              check(restored.restore(s.backup()) == 18, "Restore count");
              check(restored.restore(s.backup()) == 0, "Restore idempotence");
              Store.Reading base = s.completeScans().get(0);
              s.tune(base, 20, 1, 0, 1, "Synthetic alternate allocation");
              check(s.calibration().fatFraction == 1, "Tuning persistence");
              s.set("calibration", "original");
              check(s.calibration().fatFraction == .75, "Undo");
              java.io.ByteArrayOutputStream csv = new java.io.ByteArrayOutputStream();
              s.writeCsv(csv);
              check(csv.toString("UTF-8").contains("body_fat_original_percent"), "CSV provenance");
              JSONObject invalid = s.backup();
              invalid.getJSONArray("observations").getJSONObject(0).put("measured_at", "invalid");
              try {
                restored.restore(invalid);
                throw new AssertionError("Invalid restore accepted");
              } catch (java.time.format.DateTimeParseException expected) {
              }
              restored.db.close();
              // In-memory display fixture; never overwrite the installed app's database.
              a.store = s;
              a.db = s.db;
              a.health = new HealthBridge(a);
              s.set("theme", "dark");
              a.palette();
              a.showPage(0);
            } catch (Throwable e) {
              error[0] = e;
            }
          });
      if (error[0] != null) throw new RuntimeException(error[0]);
      for (int i = 0; i < 4; i++) {
        final int page = i;
        runOnMainSync(() -> a.showPage(page));
        waitForIdleSync();
        Thread.sleep(300);
        final Throwable[] captureError = {null};
        runOnMainSync(
            () -> {
              try {
                Bitmap bmp =
                    Bitmap.createBitmap(
                        a.shell.getWidth(), a.shell.getHeight(), Bitmap.Config.ARGB_8888);
                a.shell.draw(new Canvas(bmp));
                try (FileOutputStream out =
                    new FileOutputStream(new File(a.getCacheDir(), "synthetic-" + page + ".png"))) {
                  bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
              } catch (Throwable e) {
                captureError[0] = e;
              }
            });
        if (captureError[0] != null) throw new RuntimeException(captureError[0]);
      }
      result.putString(
          "result",
          "PASS: synthetic store, reconstruction, original preservation, backup/restore, tuning"
              + " undo, CSV and four screens");
      finish(-1, result);
    } catch (Throwable e) {
      result.putString("error", e.toString());
      finish(0, result);
    }
  }
}

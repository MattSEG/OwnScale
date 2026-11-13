package org.ownscale.app;

import android.app.*;
import android.content.*;
import android.database.*;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.os.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class HealthVerification extends Instrumentation {
  MainActivity a;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  void sync() throws Exception {
    runOnMainSync(() -> a.health.sync());
    for (int i = 0; i < 100 && a.health.syncing; i++) Thread.sleep(100);
    if (a.health.syncing) throw new AssertionError("Sync timed out");
  }

  <T extends android.health.connect.datatypes.Record> List<T> read(Class<T> type) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<T> rows = new ArrayList<>();
    final Exception[] error = {null};
    ReadRecordsRequestUsingFilters<T> request =
        new ReadRecordsRequestUsingFilters.Builder<>(type)
            .addDataOrigins(new DataOrigin.Builder().setPackageName("org.ownscale.app").build())
            .setTimeRangeFilter(
                new TimeInstantRangeFilter.Builder()
                    .setStartTime(Instant.parse("2000-01-01T00:00:00Z"))
                    .setEndTime(Instant.now())
                    .build())
            .setPageSize(1000)
            .build();
    a.getSystemService(HealthConnectManager.class)
        .readRecords(
            request,
            a.getMainExecutor(),
            new OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException>() {
              public void onResult(ReadRecordsResponse<T> response) {
                rows.addAll(response.getRecords());
                latch.countDown();
              }

              public void onError(HealthConnectException e) {
                error[0] = e;
                latch.countDown();
              }
            });
    if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Readback timed out");
    if (error[0] != null) throw error[0];
    return rows;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      a =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      .putExtra("test_mode", true));
      if (a.store.flag("sync_weight", false) && !a.health.granted(HealthBridge.WEIGHT))
        throw new AssertionError("User must grant weight write permission");
      if (a.store.flag("sync_fat", false) && !a.health.granted(HealthBridge.FAT))
        throw new AssertionError("User must grant body-fat write permission");
      sync();
      List<WeightRecord> w = read(WeightRecord.class);
      List<BodyFatRecord> f = read(BodyFatRecord.class);
      if (a.store.flag("sync_weight", false) && w.isEmpty())
        throw new AssertionError("Weight not written");
      if (a.store.flag("sync_fat", false) && f.isEmpty())
        throw new AssertionError("Body fat not written");
      for (BodyFatRecord record : f) {
        String id = record.getMetadata().getClientRecordId();
        for (Store.Reading reading : a.store.readings()) {
          if (("ownscale:" + reading.key + ":body_fat").equals(id)
              && (reading.fat == null
                  || Math.abs(record.getPercentage().getValue() - reading.fat) > 1e-8))
            throw new AssertionError("Synced body fat differs from stabilized view");
        }
      }
      sync();
      if (read(WeightRecord.class).size() != w.size()
          || read(BodyFatRecord.class).size() != f.size())
        throw new AssertionError("Duplicate Health Connect records");
      for (WeightRecord r : w)
        if (!r.getMetadata().getClientRecordId().startsWith("ownscale:"))
          throw new AssertionError("Missing stable client ID");
      long errors =
          DatabaseUtils.longForQuery(
              a.db, "SELECT count(*) FROM health_sync WHERE status='error'", null);
      if (errors != 0) throw new AssertionError("Sync errors in queue");
      result.putInt("weight_records_read_back", w.size());
      result.putInt("body_fat_records_read_back", f.size());
      result.putString(
          "result",
          "PASS: real Health Connect write/readback, source attribution and duplicate prevention");
      finish(-1, result);
    } catch (Throwable e) {
      result.putString("error", e.toString());
      finish(0, result);
    }
  }
}

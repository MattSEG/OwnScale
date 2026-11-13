package org.ownscale.app;

import android.content.pm.PackageManager;
import android.database.Cursor;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.units.*;
import android.os.*;
import java.time.*;
import java.util.*;
import org.json.*;

final class HealthBridge {
  static final String HUME = "com.elink.fittrackhealth.pro";
  static final String[] READ = {
    "android.permission.health.READ_WEIGHT",
    "android.permission.health.READ_BODY_FAT",
    "android.permission.health.READ_HEALTH_DATA_HISTORY"
  };
  static final String WEIGHT = "android.permission.health.WRITE_WEIGHT",
      FAT = "android.permission.health.WRITE_BODY_FAT";
  final MainActivity a;
  final Store store;
  boolean importing, syncing;
  Instant importEnd;

  HealthBridge(MainActivity a) {
    this.a = a;
    store = a.store;
  }

  boolean granted(String p) {
    return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
  }

  void importHistory() {
    if (importing) return;
    for (String p : READ)
      if (!granted(p)) {
        a.requestPermissions(READ, 10);
        return;
      }
    importing = true;
    importEnd = Instant.now();
    a.say("Importing Hume history…");
    read(
        WeightRecord.class,
        -1,
        () ->
            read(
                BodyFatRecord.class,
                -1,
                () -> {
                  importing = false;
                  store.set("last_import", Instant.now().toString());
                  a.say("Hume history imported. Existing readings were matched, not duplicated.");
                  a.refresh();
                }));
  }

  <T extends android.health.connect.datatypes.Record> void read(
      Class<T> type, long token, Runnable done) {
    try {
      ReadRecordsRequestUsingFilters.Builder<T> builder =
          new ReadRecordsRequestUsingFilters.Builder<>(type)
              .addDataOrigins(new DataOrigin.Builder().setPackageName(HUME).build())
              .setTimeRangeFilter(
                  new TimeInstantRangeFilter.Builder()
                      .setStartTime(Instant.parse("2000-01-01T00:00:00Z"))
                      .setEndTime(importEnd)
                      .build())
              .setPageSize(1000);
      if (token != -1) builder.setPageToken(token);
      a.getSystemService(HealthConnectManager.class)
          .readRecords(
              builder.build(),
              a.getMainExecutor(),
              new OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException>() {
                public void onResult(ReadRecordsResponse<T> response) {
                  store.db.beginTransaction();
                  try {
                    for (T r : response.getRecords()) {
                      InstantRecord ir = (InstantRecord) r;
                      boolean weight = r instanceof WeightRecord;
                      double value =
                          weight
                              ? ((WeightRecord) r).getWeight().getInGrams() / 1000.0
                              : ((BodyFatRecord) r).getPercentage().getValue();
                      JSONObject meta = new JSONObject();
                      meta.put("health_connect_id", r.getMetadata().getId());
                      meta.put("client_record_id", r.getMetadata().getClientRecordId());
                      meta.put("client_record_version", r.getMetadata().getClientRecordVersion());
                      meta.put("last_modified", r.getMetadata().getLastModifiedTime().toString());
                      meta.put(
                          "zone_offset",
                          ir.getZoneOffset() == null
                              ? JSONObject.NULL
                              : ir.getZoneOffset().toString());
                      store.db.execSQL(
                          "INSERT OR REPLACE INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
                          new Object[] {
                            "hc:" + r.getMetadata().getId(),
                            ir.getTime().toString(),
                            HUME,
                            r.getMetadata().getId(),
                            weight ? "weight" : "body_fat",
                            value,
                            weight ? "kg" : "percent",
                            weight ? "hume_reported_weight" : "hume_reported_estimate",
                            meta.toString(),
                            Instant.now().toString()
                          });
                    }
                    store.db.setTransactionSuccessful();
                  } catch (Exception e) {
                    failImport(e);
                    return;
                  } finally {
                    store.db.endTransaction();
                  }
                  if (response.getNextPageToken() != -1)
                    read(type, response.getNextPageToken(), done);
                  else done.run();
                }

                public void onError(HealthConnectException e) {
                  failImport(e);
                }
              });
    } catch (Exception e) {
      failImport(e);
    }
  }

  void failImport(Exception e) {
    importing = false;
    a.say("History import needs attention: " + e.getMessage());
  }

  void requestWrite() {
    List<String> p = new ArrayList<>();
    if (store.flag("sync_weight", false)) p.add(WEIGHT);
    if (store.flag("sync_fat", false)) p.add(FAT);
    if (p.isEmpty()) {
      a.say("Enable weight or body fat sync first.");
      return;
    }
    a.requestPermissions(p.toArray(new String[0]), 12);
  }

  static class Pending {
    String id, fingerprint;
    android.health.connect.datatypes.Record record;
  }

  void sync() {
    if (syncing) return;
    boolean weight = store.flag("sync_weight", false), fat = store.flag("sync_fat", false);
    if (!weight && !fat) return;
    if (weight && !granted(WEIGHT) || fat && !granted(FAT)) {
      store.set("sync_status", "Permission needed · tap Allow access");
      a.refresh();
      return;
    }
    List<Pending> queue = new ArrayList<>();
    long version = System.currentTimeMillis();
    for (Store.Reading r : store.readings()) {
      if (r.source.equals("Hume") || r.excluded) continue;
      for (String metric : new String[] {"weight", "body_fat"}) {
        Double value =
            metric.equals("weight")
                ? (weight ? r.weight : null)
                : (fat && r.experimental ? r.fat : null);
        if (value == null) continue;
        Pending p = new Pending();
        p.id = r.key + ":" + metric;
        p.fingerprint =
            r.time + "|" + value + "|" + (metric.equals("body_fat") ? r.calibrationId : "");
        try (Cursor c =
            store.db.rawQuery(
                "SELECT fingerprint,status FROM health_sync WHERE observation_id=?",
                new String[] {p.id})) {
          if (c.moveToFirst()
              && c.getString(0).equals(p.fingerprint)
              && c.getString(1).equals("synced")) continue;
        }
        Metadata m =
            new Metadata.Builder()
                .setClientRecordId("ownscale:" + p.id)
                .setClientRecordVersion(version)
                .setRecordingMethod(
                    r.source.equals("Manual")
                        ? Metadata.RECORDING_METHOD_MANUAL_ENTRY
                        : Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
                .setDevice(
                    new Device.Builder()
                        .setManufacturer("Hume")
                        .setModel("Body Pod")
                        .setType(Device.DEVICE_TYPE_SCALE)
                        .build())
                .build();
        Instant time = Instant.parse(r.time);
        ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(time);
        p.record =
            metric.equals("weight")
                ? new WeightRecord.Builder(m, time, Mass.fromGrams(value * 1000))
                    .setZoneOffset(offset)
                    .build()
                : new BodyFatRecord.Builder(m, time, Percentage.fromValue(value))
                    .setZoneOffset(offset)
                    .build();
        queue.add(p);
      }
    }
    if (queue.isEmpty()) {
      store.set("sync_status", "Up to date");
      a.refresh();
      return;
    }
    syncing = true;
    store.set("sync_status", "Syncing " + queue.size() + " values…");
    a.refresh();
    write(queue, 0);
  }

  void write(List<Pending> queue, int offset) {
    int end = Math.min(offset + 100, queue.size());
    List<android.health.connect.datatypes.Record> records = new ArrayList<>();
    for (int i = offset; i < end; i++) records.add(queue.get(i).record);
    try {
      a.getSystemService(HealthConnectManager.class)
          .insertRecords(
              records,
              a.getMainExecutor(),
              new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                public void onResult(InsertRecordsResponse response) {
                  store.db.beginTransaction();
                  try {
                    for (int i = offset; i < end; i++) {
                      Pending p = queue.get(i);
                      String id = response.getRecords().get(i - offset).getMetadata().getId();
                      store.db.execSQL(
                          "INSERT OR REPLACE INTO health_sync VALUES(?,?,?,?,?,NULL)",
                          new Object[] {
                            p.id, p.fingerprint, id, "synced", Instant.now().toString()
                          });
                    }
                    store.db.setTransactionSuccessful();
                  } finally {
                    store.db.endTransaction();
                  }
                  if (end < queue.size()) write(queue, end);
                  else {
                    syncing = false;
                    store.set("last_sync", Instant.now().toString());
                    store.set("sync_status", "Up to date");
                    a.say("Health Connect is up to date.");
                    a.refresh();
                  }
                }

                public void onError(HealthConnectException e) {
                  failed(queue, offset, end, e);
                }
              });
    } catch (Exception e) {
      failed(queue, offset, end, e);
    }
  }

  void failed(List<Pending> queue, int start, int end, Exception e) {
    syncing = false;
    for (int i = start; i < end; i++) {
      Pending p = queue.get(i);
      store.db.execSQL(
          "INSERT OR REPLACE INTO health_sync VALUES(?,?,NULL,'error',NULL,?)",
          new Object[] {p.id, p.fingerprint, e.getMessage()});
    }
    store.set("sync_status", "Retry needed · " + e.getMessage());
    a.say("Health sync needs retry. Your local data is safe.");
    a.refresh();
  }
}

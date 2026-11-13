package org.ownscale.app;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;
import org.json.*;

final class Store {
  final SQLiteDatabase db;
  static final String[] BACKUP_TABLES = {
    "observations",
    "capture_sessions",
    "raw_packets",
    "model_baselines",
    "calibrations",
    "annotations",
    "app_config"
  };

  Store(Context context) {
    db = context.openOrCreateDatabase("ownscale.db", 0, null);
    initialize(db);
    if (get("migrated_v2", "").isEmpty()) {
      SharedPreferences old = context.getSharedPreferences("MainActivity", 0);
      set("height", old.getString("height", ""));
      set("age", old.getString("age", ""));
      set("sex", Integer.toString(old.getInt("sex", 0)));
      set("activity", Integer.toString(old.getInt("activity", 0)));
      set("units", "lb");
      set("theme", "dark");
      set("auto_connect", "true");
      set("sync_weight", "false");
      set("migrated_v2", "yes");
      try (Cursor c =
          db.rawQuery(
              "SELECT device_address FROM capture_sessions ORDER BY started_at DESC LIMIT 1",
              null)) {
        if (c.moveToFirst()) set("device", c.getString(0));
      }
    }
    seedCalibration();
  }

  Store(SQLiteDatabase database) {
    db = database;
    initialize(db);
  }

  static void initialize(SQLiteDatabase db) {
    db.execSQL("PRAGMA foreign_keys=ON");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS observations(id TEXT PRIMARY KEY, measured_at TEXT NOT NULL,"
            + " source TEXT NOT NULL, source_record_id TEXT, metric TEXT NOT NULL, value REAL NOT"
            + " NULL, unit TEXT NOT NULL, method TEXT NOT NULL, metadata_json TEXT NOT NULL DEFAULT"
            + " '{}', imported_at TEXT NOT NULL)");
    db.execSQL("CREATE INDEX IF NOT EXISTS observation_time ON observations(measured_at)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS capture_sessions(id TEXT PRIMARY KEY, started_at TEXT NOT NULL,"
            + " ended_at TEXT, device_name TEXT, device_address TEXT, profile_hex TEXT, status TEXT"
            + " NOT NULL)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS raw_packets(id INTEGER PRIMARY KEY,session_id TEXT NOT NULL"
            + " REFERENCES capture_sessions(id),received_at TEXT NOT NULL,characteristic TEXT NOT"
            + " NULL,hex TEXT NOT NULL,checksum_valid INTEGER NOT NULL)");
    boolean packetUid = false;
    try (Cursor c = db.rawQuery("PRAGMA table_info(raw_packets)", null)) {
      while (c.moveToNext()) if (c.getString(1).equals("packet_uid")) packetUid = true;
    }
    if (!packetUid) db.execSQL("ALTER TABLE raw_packets ADD COLUMN packet_uid TEXT");
    db.execSQL(
        "UPDATE raw_packets SET packet_uid=session_id || ':' || id WHERE packet_uid IS NULL");
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS raw_packet_identity ON raw_packets(packet_uid)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS model_baselines(id TEXT PRIMARY KEY,session_id TEXT NOT"
            + " NULL,assumed_percent REAL NOT NULL,model_percent REAL NOT NULL,height_cm INTEGER"
            + " NOT NULL,sex INTEGER NOT NULL,created_at TEXT NOT NULL,provenance TEXT NOT NULL)");
    db.execSQL("CREATE TABLE IF NOT EXISTS app_config(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS calibrations(id TEXT PRIMARY KEY,baseline_session TEXT NOT"
            + " NULL,anchor REAL NOT NULL,model REAL NOT NULL,sensitivity REAL NOT NULL,height_cm"
            + " INTEGER NOT NULL,sex INTEGER NOT NULL,created_at TEXT NOT NULL,note TEXT NOT"
            + " NULL)");
    boolean legacyColumn = false;
    try (Cursor c = db.rawQuery("PRAGMA table_info(calibrations)", null)) {
      while (c.moveToNext()) if (c.getString(1).equals("legacy_offset")) legacyColumn = true;
    }
    if (!legacyColumn) db.execSQL("ALTER TABLE calibrations ADD COLUMN legacy_offset REAL");
    boolean fractionColumn = false;
    try (Cursor c = db.rawQuery("PRAGMA table_info(calibrations)", null)) {
      while (c.moveToNext()) if (c.getString(1).equals("fat_fraction")) fractionColumn = true;
    }
    if (!fractionColumn)
      db.execSQL("ALTER TABLE calibrations ADD COLUMN fat_fraction REAL NOT NULL DEFAULT 0.75");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS annotations(reading_key TEXT PRIMARY KEY,note TEXT NOT"
            + " NULL,excluded INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS health_sync(observation_id TEXT PRIMARY KEY,fingerprint TEXT"
            + " NOT NULL,health_id TEXT,status TEXT NOT NULL,synced_at TEXT,error TEXT)");
    db.execSQL("PRAGMA user_version=2");
  }

  String get(String key, String def) {
    try (Cursor c = db.rawQuery("SELECT value FROM app_config WHERE key=?", new String[] {key})) {
      return c.moveToFirst() ? c.getString(0) : def;
    }
  }

  void set(String key, String value) {
    db.execSQL(
        "INSERT OR REPLACE INTO app_config VALUES(?,?)",
        new Object[] {key, value == null ? "" : value});
  }

  boolean flag(String key, boolean def) {
    return Boolean.parseBoolean(get(key, Boolean.toString(def)));
  }

  int integer(String key, int def) {
    try {
      return Integer.parseInt(get(key, ""));
    } catch (Exception e) {
      return def;
    }
  }

  double number(String key, double def) {
    try {
      return Double.parseDouble(get(key, ""));
    } catch (Exception e) {
      return def;
    }
  }

  void seedCalibration() {
    if (!get("calibration", "").isEmpty()) {
      seedLegacyOffsets();
      return;
    }
    try (Cursor c = db.rawQuery("SELECT * FROM model_baselines LIMIT 1", null)) {
      if (c.moveToFirst()) {
        db.execSQL(
            "INSERT OR IGNORE INTO"
                + " calibrations(id,baseline_session,anchor,model,sensitivity,height_cm,sex,created_at,note)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            new Object[] {
              "original",
              c.getString(1),
              c.getDouble(2),
              c.getDouble(3),
              1.0,
              c.getInt(4),
              c.getInt(5),
              c.getString(6),
              c.getString(7)
            });
        set("calibration", "original");
      }
    }
    seedLegacyOffsets();
  }

  void seedLegacyOffsets() {
    try (Cursor base =
        db.rawQuery(
            "SELECT b.model_percent,s.started_at FROM model_baselines b JOIN capture_sessions s ON"
                + " s.id=b.session_id ORDER BY b.created_at LIMIT 1",
            null)) {
      if (!base.moveToFirst()) return;
      double originalModel = base.getDouble(0);
      try (Cursor hume =
          db.rawQuery(
              "SELECT value FROM observations WHERE source='com.elink.fittrackhealth.pro' AND"
                  + " metric='body_fat' AND measured_at<=? ORDER BY measured_at DESC LIMIT 1",
              new String[] {base.getString(1)})) {
        if (!hume.moveToFirst()) return;
        db.execSQL(
            "UPDATE calibrations SET legacy_offset=anchor+sensitivity*(?-model)-? WHERE"
                + " legacy_offset IS NULL",
            new Object[] {originalModel, hume.getDouble(0)});
      }
    }
  }

  static class Calibration {
    String id, session, note;
    double anchor, model, sensitivity;
    double fatFraction = .75;
    Double legacyOffset;
    int height, sex;
  }

  Calibration calibration() {
    seedCalibration();
    try (Cursor c =
        db.rawQuery(
            "SELECT * FROM calibrations WHERE id=?",
            new String[] {get("calibration", "original")})) {
      if (!c.moveToFirst()) return null;
      Calibration k = new Calibration();
      k.id = c.getString(0);
      k.session = c.getString(1);
      k.anchor = c.getDouble(2);
      k.model = c.getDouble(3);
      k.sensitivity = c.getDouble(4);
      k.height = c.getInt(5);
      k.sex = c.getInt(6);
      k.note = c.getString(8);
      k.legacyOffset = c.isNull(9) ? null : c.getDouble(9);
      k.fatFraction = c.getDouble(10);
      return k;
    }
  }

  String tune(Reading baseline, double anchor, double sensitivity, String note) {
    Calibration cal = calibration();
    return tune(
        baseline,
        anchor,
        sensitivity,
        cal != null && cal.legacyOffset != null ? cal.legacyOffset : 0,
        note);
  }

  String tune(
      Reading baseline, double anchor, double sensitivity, double legacyOffset, String note) {
    Calibration current = calibration();
    return tune(
        baseline,
        anchor,
        sensitivity,
        legacyOffset,
        current == null ? .75 : current.fatFraction,
        note);
  }

  String tune(
      Reading baseline,
      double anchor,
      double sensitivity,
      double legacyOffset,
      double fatFraction,
      String note) {
    if (!Double.isFinite(fatFraction)
        || fatFraction < 0
        || fatFraction > 1
        || baseline.weight == null
        || baseline.weight <= 0)
      throw new IllegalArgumentException(
          "Choose a reference with weight and a fat share from 0 to 100%.");
    if (!Double.isFinite(legacyOffset)
        || Math.abs(legacyOffset) >= 100
        || baseline.model == null
        || anchor <= 0
        || anchor >= 100
        || !Double.isFinite(anchor)
        || !Double.isFinite(sensitivity)
        || sensitivity < 0
        || sensitivity > 2)
      throw new IllegalArgumentException(
          "Choose a complete scan, body fat between 0 and 100, and sensitivity from 0 to 2.");
    String id = UUID.randomUUID().toString();
    db.execSQL(
        "INSERT INTO calibrations VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        new Object[] {
          id,
          baseline.session,
          anchor,
          baseline.model,
          sensitivity,
          baseline.height,
          baseline.sex,
          Instant.now().toString(),
          note,
          legacyOffset,
          fatFraction
        });
    set("calibration", id);
    return id;
  }

  static class Observation {
    String id, time, source, recordId, metric, unit, method, json;
    double value;
  }

  static class Reading {
    String key, time, source, session, note = "", quality = "", calibrationId = "";
    Double weight, fat, adjustedFat, originalFat, model, waist;
    boolean excluded, experimental, legacyAdjusted;
    Double repeatDifference;
    int height, sex;
    List<Observation> observations = new ArrayList<>();
  }

  List<Reading> readings() {
    LinkedHashMap<String, Reading> map = new LinkedHashMap<>();
    Calibration calibration = calibration();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,measured_at,source,source_record_id,metric,value,unit,method,metadata_json"
                + " FROM observations ORDER BY measured_at DESC,id",
            null)) {
      while (c.moveToNext()) {
        Observation o = new Observation();
        o.id = c.getString(0);
        o.time = c.getString(1);
        o.source = c.getString(2);
        o.recordId = c.getString(3);
        o.metric = c.getString(4);
        o.value = c.getDouble(5);
        o.unit = c.getString(6);
        o.method = c.getString(7);
        o.json = c.getString(8);
        boolean local = o.source.equals("hume_ble") || o.source.equals("ownscale_experimental");
        String key =
            local
                ? "scan:" + o.recordId
                : o.source.equals("manual") ? "manual:" + o.recordId : "hume:" + o.time;
        Reading r = map.get(key);
        if (r == null) {
          r = new Reading();
          r.key = key;
          r.time = o.time;
          r.source = local ? "OwnScale" : o.source.equals("manual") ? "Manual" : "Hume";
          r.session = local ? o.recordId : null;
          map.put(key, r);
        }
        r.observations.add(o);
        if (o.metric.equals("weight")) r.weight = o.value;
        if (o.metric.equals("waist")) r.waist = o.value;
        if (o.metric.equals("body_fat")) {
          r.fat = r.originalFat = o.value;
          r.experimental = local;
          if (local) {
            try {
              JSONObject meta = new JSONObject(o.json);
              r.model = meta.getDouble("unadjusted_model_percent");
              r.height = meta.getInt("height_cm");
              r.sex = meta.getInt("sex_code");
              if (calibration != null) {
                r.calibrationId = calibration.id;
                if (calibration.height == r.height && calibration.sex == r.sex) {
                  double v =
                      calibration.anchor + calibration.sensitivity * (r.model - calibration.model);
                  r.fat = Double.isFinite(v) && v > 0 && v < 100 ? v : null;
                } else r.fat = null;
              }
            } catch (Exception ignored) {
              r.fat = null;
            }
          }
        }
      }
    }
    Map<String, String[]> notes = new HashMap<>();
    try (Cursor c = db.rawQuery("SELECT * FROM annotations", null)) {
      while (c.moveToNext())
        notes.put(c.getString(0), new String[] {c.getString(1), c.getString(2)});
    }
    Double referenceWeight = referenceWeight(new ArrayList<>(map.values()), calibration);
    for (Reading r : map.values()) {
      if (r.source.equals("Hume") && r.originalFat != null && calibration != null) {
        r.fat =
            r.weight == null || referenceWeight == null
                ? null
                : Lookback.estimate(
                    r.weight, referenceWeight, calibration.anchor, calibration.fatFraction);
        r.legacyAdjusted = true;
        r.calibrationId = calibration.id;
      }
      String[] n = notes.get(r.key);
      if (n != null) {
        r.note = n[0];
        r.excluded = n[1].equals("1");
      }
      r.quality =
          r.source.equals("Hume")
              ? (r.legacyAdjusted ? "Weight-informed history" : "Imported history")
              : r.source.equals("Manual")
                  ? "Manual entry"
                  : r.model != null
                      ? (r.fat == null ? "Scan saved · review tuning" : "Complete scan")
                      : r.observations.stream()
                                  .filter(o -> o.metric.startsWith("impedance_"))
                                  .count()
                              >= 10
                          ? "Scan saved · estimate unavailable"
                          : r.observations.stream().anyMatch(o -> o.metric.startsWith("impedance_"))
                              ? "Partial scan · weight saved"
                              : r.weight != null ? "Weight only" : "Incomplete scan";
    }
    for (Reading r : map.values()) r.adjustedFat = r.fat;
    List<Reading> ordered = new ArrayList<>(map.values());
    for (int i = 0; i < ordered.size(); i++) {
      Reading current = ordered.get(i);
      if (!current.experimental
          || current.fat == null
          || current.weight == null
          || current.excluded) continue;
      for (int j = i + 1; j < ordered.size(); j++) {
        Reading earlier = ordered.get(j);
        long seconds =
            java.time.Duration.between(Instant.parse(earlier.time), Instant.parse(current.time))
                .getSeconds();
        if (seconds > 7200) break;
        if (earlier.experimental
            && !earlier.excluded
            && earlier.fat != null
            && earlier.weight != null
            && Math.abs(current.weight - earlier.weight) <= .25) {
          double delta = Math.abs(current.fat - earlier.fat);
          if (delta >= .5) {
            current.repeatDifference = delta;
            current.quality = "Repeat-scan variation";
          }
          break;
        }
      }
    }
    stabilize(ordered, calibration, number("bf_response_hours", 72));
    return ordered;
  }

  static Double referenceWeight(List<Reading> readings, Calibration calibration) {
    if (calibration == null) return null;
    for (Reading r : readings) if (calibration.session.equals(r.session)) return r.weight;
    return null;
  }

  static void stabilize(List<Reading> readings, Calibration calibration, double hours) {
    if (!Double.isFinite(hours) || hours < 0) hours = 72;
    if (hours == 0 || calibration == null) return;
    List<Reading> own = new ArrayList<>(), legacy = new ArrayList<>();
    for (Reading r : readings) {
      if (r.excluded) {
        r.fat = null;
        continue;
      }
      if (r.adjustedFat == null) continue;
      if (r.experimental
          && r.model != null
          && r.height == calibration.height
          && r.sex == calibration.sex) own.add(r);
      else if (r.legacyAdjusted) legacy.add(r);
    }
    own.sort(Comparator.comparing(r -> Instant.parse(r.time)));
    legacy.sort(Comparator.comparing(r -> Instant.parse(r.time)));
    List<TrendMath.Point> raw = new ArrayList<>();
    for (Reading r : own)
      raw.add(new TrendMath.Point(Instant.parse(r.time).toEpochMilli(), r.model));
    List<TrendMath.Point> filtered = Stability.filter(raw, hours);
    double reference = calibration.model;
    for (int i = 0; i < own.size(); i++)
      if (own.get(i).session.equals(calibration.session)) reference = filtered.get(i).value;
    for (int i = 0; i < own.size(); i++) {
      double v = calibration.anchor + calibration.sensitivity * (filtered.get(i).value - reference);
      own.get(i).fat = v > 0 && v < 100 ? v : null;
    }
    raw.clear();
    for (Reading r : legacy)
      raw.add(new TrendMath.Point(Instant.parse(r.time).toEpochMilli(), r.weight, true));
    filtered = Stability.filter(raw, hours);
    Double referenceKg = referenceWeight(readings, calibration);
    for (int i = 0; i < legacy.size(); i++)
      legacy.get(i).fat =
          referenceKg == null
              ? null
              : Lookback.estimate(
                  filtered.get(i).value, referenceKg, calibration.anchor, calibration.fatFraction);
  }

  List<Reading> completeScans() {
    List<Reading> result = new ArrayList<>();
    for (Reading r : readings()) if (r.model != null && !r.excluded) result.add(r);
    return result;
  }

  void annotate(Reading r, String note, boolean excluded) {
    db.execSQL(
        "INSERT OR REPLACE INTO annotations VALUES(?,?,?)",
        new Object[] {r.key, note, excluded ? 1 : 0});
  }

  void manual(Instant time, Double kg, Double waist, String note) {
    if (time.isAfter(Instant.now().plusSeconds(60)))
      throw new IllegalArgumentException("Choose a date and time that has already occurred.");
    if (kg != null && (!Double.isFinite(kg) || kg < 1 || kg > 350)
        || waist != null && (!Double.isFinite(waist) || waist < 20 || waist > 300))
      throw new IllegalArgumentException("Check weight and waist values.");
    if (kg == null && waist == null) throw new IllegalArgumentException("Enter weight or waist.");
    String id = UUID.randomUUID().toString();
    db.beginTransaction();
    try {
      if (kg != null) manualObservation(id, time, "weight", kg, "kg");
      if (waist != null) manualObservation(id, time, "waist", waist, "cm");
      db.execSQL("INSERT INTO annotations VALUES(?,?,0)", new Object[] {"manual:" + id, note});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void manualObservation(String id, Instant time, String metric, double value, String unit) {
    db.execSQL(
        "INSERT INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
        new Object[] {
          "manual:" + id + ":" + metric,
          time.toString(),
          "manual",
          id,
          metric,
          value,
          unit,
          "manual_entry",
          "{}",
          Instant.now().toString()
        });
  }

  JSONArray tableJson(String table) throws Exception {
    if (!Arrays.asList(BACKUP_TABLES).contains(table))
      throw new IllegalArgumentException("Unknown table");
    JSONArray rows = new JSONArray();
    try (Cursor c = db.rawQuery("SELECT * FROM " + table, null)) {
      while (c.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
          Object value =
              c.isNull(i)
                  ? JSONObject.NULL
                  : c.getType(i) == Cursor.FIELD_TYPE_INTEGER
                      ? c.getLong(i)
                      : c.getType(i) == Cursor.FIELD_TYPE_FLOAT ? c.getDouble(i) : c.getString(i);
          row.put(c.getColumnName(i), value);
        }
        rows.put(row);
      }
    }
    return rows;
  }

  JSONObject backup() throws Exception {
    JSONObject result = new JSONObject();
    result.put("format", "ownscale-backup");
    result.put("schema_version", 2);
    result.put("exported_at", Instant.now().toString());
    for (String t : BACKUP_TABLES) result.put(t, tableJson(t));
    return result;
  }

  int restore(JSONObject root) throws Exception {
    int version = root.getInt("schema_version");
    if (version < 1 || version > 2 || !root.has("observations"))
      throw new IllegalArgumentException("Unsupported backup");
    int added = 0;
    db.beginTransaction();
    try {
      for (String table :
          new String[] {
            "capture_sessions",
            "observations",
            "model_baselines",
            "raw_packets",
            "calibrations",
            "annotations",
            "app_config"
          }) {
        JSONArray rows = root.optJSONArray(table);
        if (rows == null) continue;
        Set<String> columns = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
          while (c.moveToNext()) columns.add(c.getString(1));
        }
        for (int i = 0; i < rows.length(); i++) {
          JSONObject row = rows.getJSONObject(i);
          ContentValues v = new ContentValues();
          for (Iterator<String> keys = row.keys(); keys.hasNext(); ) {
            String k = keys.next();
            if (!columns.contains(k))
              throw new IllegalArgumentException("Unexpected backup column: " + k);
            if (table.equals("raw_packets") && k.equals("id")) continue;
            Object value = row.get(k);
            if (value == JSONObject.NULL) v.putNull(k);
            else if (value instanceof Number) v.put(k, value.toString());
            else v.put(k, value.toString());
          }
          if (table.equals("observations")) {
            Instant.parse(row.getString("measured_at"));
            if (!Double.isFinite(row.getDouble("value")))
              throw new IllegalArgumentException("Invalid observation");
          }
          if (table.equals("raw_packets")) {
            String uid =
                row.optString("packet_uid", row.getString("session_id") + ":" + row.getLong("id"));
            v.put("packet_uid", uid);
            try (Cursor c =
                db.rawQuery("SELECT 1 FROM raw_packets WHERE packet_uid=?", new String[] {uid})) {
              if (c.moveToFirst()) continue;
            }
          }
          // Restore annotations/configuration deliberately; immutable measurement IDs never
          // overwrite existing readings.
          long n =
              db.insertWithOnConflict(
                  table,
                  null,
                  v,
                  table.equals("annotations") || table.equals("app_config")
                      ? SQLiteDatabase.CONFLICT_REPLACE
                      : SQLiteDatabase.CONFLICT_IGNORE);
          if (n != -1 && table.equals("observations")) added++;
        }
      }
      set("sync_weight", "false");
      set("sync_fat", "false");
      set("auto_connect", "false");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    seedCalibration();
    return added;
  }

  static String csv(String s) {
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  void writeCsv(java.io.OutputStream out) throws Exception {
    java.io.Writer w = new java.io.OutputStreamWriter(out, "UTF-8");
    w.write(
        "measured_at,source,weight_kg,body_fat_percent,body_fat_individual_percent,body_fat_method,waist_cm,excluded,note,reading_id,calibration_id,body_fat_original_percent,lookback_fat_fraction\r\n");
    Calibration exportCalibration = calibration();
    for (Reading r : readings()) {
      String[] values = {
        r.time,
        r.source,
        r.weight == null ? "" : Double.toString(r.weight),
        r.fat == null ? "" : Double.toString(r.fat),
        r.adjustedFat == null ? "" : Double.toString(r.adjustedFat),
        r.experimental
            ? number("bf_response_hours", 72) > 0
                ? "experimental_personal_time_stabilized"
                : "experimental_personal_adjustment"
            : r.legacyAdjusted
                ? number("bf_response_hours", 72) > 0
                    ? "hume_weight_reconstruction_time_stabilized"
                    : "hume_weight_reconstruction"
                : r.fat != null ? "hume_reported_estimate" : "",
        r.waist == null ? "" : Double.toString(r.waist),
        Boolean.toString(r.excluded),
        r.note,
        r.key,
        r.calibrationId,
        r.originalFat == null ? "" : Double.toString(r.originalFat),
        r.legacyAdjusted && exportCalibration != null
            ? Double.toString(exportCalibration.fatFraction)
            : ""
      };
      for (int i = 0; i < values.length; i++) {
        if (i > 0) w.write(",");
        String value = values[i];
        if ((i == 8) && value.matches("(?s)^\\s*[=+@-].*")) value = "'" + value;
        w.write(csv(value));
      }
      w.write("\r\n");
    }
    w.flush();
  }
}

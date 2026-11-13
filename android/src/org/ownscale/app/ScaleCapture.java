package org.ownscale.app;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.time.Instant;
import java.util.*;

class ScaleCapture {
  final MainActivity a;
  final SQLiteDatabase db;
  final Handler h = new Handler(Looper.getMainLooper());
  BluetoothLeScanner scanner;
  BluetoothGatt gatt;
  String session;
  byte[] profile;
  boolean scanning = false;
  int generation = 0;
  boolean low = false, high = false, estimated = false;
  Double stableKg;
  double[] lowSegments;
  final ArrayList<BluetoothGattDescriptor> subscriptions = new ArrayList<>();

  static UUID uuid(String n) {
    return UUID.fromString("0000" + n + "-0000-1000-8000-00805f9b34fb");
  }

  ScaleCapture(MainActivity a) {
    this.a = a;
    db = a.db;
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS capture_sessions(id TEXT PRIMARY KEY, started_at TEXT NOT NULL,"
            + " ended_at TEXT, device_name TEXT, device_address TEXT, profile_hex TEXT, status TEXT"
            + " NOT NULL)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS raw_packets(id INTEGER PRIMARY KEY, session_id TEXT NOT NULL"
            + " REFERENCES capture_sessions(id), received_at TEXT NOT NULL, characteristic TEXT NOT"
            + " NULL, hex TEXT NOT NULL, checksum_valid INTEGER NOT NULL)");
  }

  void start(byte[] p) {
    if (scanning || gatt != null) return;
    profile = p;
    low = high = false;
    estimated = false;
    stableKg = null;
    lowSegments = null;
    BluetoothAdapter adapter = a.getSystemService(BluetoothManager.class).getAdapter();
    if (adapter == null || !adapter.isEnabled()) {
      a.say("Turn on Bluetooth first.");
      return;
    }
    scanner = adapter.getBluetoothLeScanner();
    scanning = true;
    a.captureActive(true);
    int gen = ++generation;
    a.say("Scanning for Body Pod for 20 seconds. Wake the scale.");
    scanner.startScan(
        null,
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
        scan);
    h.postDelayed(
        () -> {
          if (gen == generation && scanning)
            stop("No Body Pod found. Wake it and retry; close Hume if it holds the connection.");
        },
        20000);
  }

  final ScanCallback scan =
      new ScanCallback() {
        @Override
        public void onScanFailed(int error) {
          h.post(() -> stop("Bluetooth scan failed: " + error));
        }

        @Override
        public void onScanResult(int type, ScanResult r) {
          String name = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
          if (name == null) name = r.getDevice().getName();
          if (name == null
              || !(name.toLowerCase(Locale.US).contains("body pod")
                  || name.toLowerCase(Locale.US).contains("hume"))) return;
          final String found = name;
          h.post(
              () -> {
                if (!scanning) return;
                String remembered = a.store.get("device", "");
                if (!remembered.isEmpty() && !remembered.equals(r.getDevice().getAddress())) return;
                scanning = false;
                scanner.stopScan(scan);
                session = UUID.randomUUID().toString();
                db.execSQL(
                    "INSERT INTO capture_sessions VALUES(?,?,NULL,?,?,?,?)",
                    new Object[] {
                      session,
                      Instant.now().toString(),
                      found,
                      r.getDevice().getAddress(),
                      Protocol.hex(profile),
                      "connecting"
                    });
                a.say("Connecting to " + found);
                gatt = r.getDevice().connectGatt(a, false, callback, BluetoothDevice.TRANSPORT_LE);
                int gen = generation;
                h.postDelayed(
                    () -> {
                      if (gen == generation)
                        stop("Session ended after 120 seconds; any captured data is saved.");
                    },
                    120000);
              });
        }
      };
  final BluetoothGattCallback callback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
          h.post(
              () -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  stop("Bluetooth connection error " + status);
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                  a.say("Discovering scale services…");
                  g.discoverServices();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED)
                  stop("Scale disconnected. Captured data is saved.");
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          h.post(
              () -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(uuid("fff0"));
                if (status != 0 || service == null) {
                  stop(
                      "This scale does not expose the documented FFF0 service. Capture needs"
                          + " investigation.");
                  return;
                }
                subscriptions.clear();
                for (String id : new String[] {"fff4", "fff2"}) {
                  BluetoothGattCharacteristic ch = service.getCharacteristic(uuid(id));
                  if (ch == null) continue;
                  BluetoothGattDescriptor d = ch.getDescriptor(uuid("2902"));
                  if (d != null && g.setCharacteristicNotification(ch, true)) {
                    d.setValue(
                        (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    subscriptions.add(d);
                  }
                }
                if (subscriptions.isEmpty()) {
                  stop("No notification channels available.");
                  return;
                }
                subscribe();
              });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
          h.post(
              () -> {
                if (g != gatt) return;
                if (status != 0) {
                  stop("Notification subscription failed: " + status);
                  return;
                }
                subscribe();
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
          h.post(
              () -> {
                if (g != gatt) return;
                if (status != 0) stop("Profile write failed: " + status);
                else {
                  a.store.set("device", g.getDevice().getAddress());
                  a.say("Connected. Step on barefoot and hold the handle.");
                }
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
          byte[] copy = value.clone();
          h.post(
              () -> {
                if (g == gatt) packet(c.getUuid().toString(), copy);
              });
        }
      };

  void subscribe() {
    if (!subscriptions.isEmpty()) {
      if (!gatt.writeDescriptor(subscriptions.remove(0))) stop("Could not subscribe to scale.");
      return;
    }
    BluetoothGattCharacteristic ch = gatt.getService(uuid("fff0")).getCharacteristic(uuid("fff1"));
    if (ch == null) {
      stop("Profile characteristic missing.");
      return;
    }
    ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    ch.setValue(profile);
    if (!gatt.writeCharacteristic(ch)) stop("Could not send profile.");
  }

  void packet(String characteristic, byte[] p) {
    String now = Instant.now().toString();
    db.execSQL(
        "INSERT INTO"
            + " raw_packets(session_id,received_at,characteristic,hex,checksum_valid,packet_uid)"
            + " VALUES(?,?,?,?,?,?)",
        new Object[] {
          session,
          now,
          characteristic,
          Protocol.hex(p),
          Protocol.checksum(p) ? 1 : 0,
          UUID.randomUUID().toString()
        });
    Double weight = Protocol.stableWeight(p);
    if (weight == null && p.length == 11 && (p[0] & 255) == 207 && Protocol.checksum(p)) {
      double live = Protocol.u16(p, 3) / 100.0;
      if (live > 1 && live < 350)
        a.say("Finding stable weight · " + a.weight(live) + " " + a.weightUnit());
    }
    if (weight != null) {
      stableKg = weight;
      save("weight", weight, "kg", "lefu_cf_v1_provisional", now);
      a.say(
          String.format(
              Locale.US,
              "Stable weight %.2f kg / %.1f lb. Waiting for impedance…",
              weight,
              weight * 2.20462262185));
      a.refresh();
    }
    double[] z = Protocol.impedance(p);
    if (z != null) {
      int index = p[1] & 255;
      for (int i = 0; i < 5; i++)
        save(
            "impedance_group_" + index + "_segment_" + i,
            z[i],
            "ohm",
            "lefu_be_v1_provisional",
            now);
      if (index == 0) {
        low = true;
        lowSegments = z;
      } else high = true;
      a.say("Measuring body composition…");
      if (low && high) {
        int gen = generation;
        h.postDelayed(
            () -> {
              if (gen == generation) stop("Measurement saved. Your morning reading is ready.");
            },
            5000);
      }
    }
  }

  void recoverEstimates() {
    try (android.database.Cursor sessions =
        db.rawQuery(
            "SELECT id,profile_hex FROM capture_sessions WHERE ended_at IS NOT NULL ORDER BY"
                + " started_at",
            null)) {
      while (sessions.moveToNext()) {
        String saved = sessions.getString(0);
        try (android.database.Cursor exists =
            db.rawQuery(
                "SELECT 1 FROM observations WHERE id=?",
                new String[] {"ble:" + saved + ":body_fat"})) {
          if (exists.moveToFirst()) continue;
        }
        session = saved;
        profile = fromHex(sessions.getString(1));
        low = high = estimated = false;
        stableKg = null;
        lowSegments = null;
        String lastTime = null;
        try (android.database.Cursor packets =
            db.rawQuery(
                "SELECT received_at,hex FROM raw_packets WHERE session_id=? ORDER BY id",
                new String[] {session})) {
          while (packets.moveToNext()) {
            lastTime = packets.getString(0);
            byte[] p = fromHex(packets.getString(1));
            Double w = Protocol.stableWeight(p);
            if (w != null) stableKg = w;
            double[] z = Protocol.impedance(p);
            if (z != null) {
              if (p[1] == 0) {
                low = true;
                lowSegments = z;
              } else high = true;
            }
          }
        }
        if (low && high && stableKg != null && profile.length == 8) estimate(lastTime);
      }
    } finally {
      session = null;
      profile = null;
      low = high = estimated = false;
      stableKg = null;
      lowSegments = null;
    }
  }

  static byte[] fromHex(String text) {
    byte[] p = new byte[text.length() / 2];
    for (int i = 0; i < p.length; i++)
      p[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
    return p;
  }

  void estimate(String now) {
    if (estimated || stableKg == null || lowSegments == null) return;
    try {
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS model_baselines(id TEXT PRIMARY KEY, session_id TEXT NOT"
              + " NULL, assumed_percent REAL NOT NULL, model_percent REAL NOT NULL, height_cm"
              + " INTEGER NOT NULL, sex INTEGER NOT NULL, created_at TEXT NOT NULL, provenance TEXT"
              + " NOT NULL)");
      int height = profile[4] & 255, sex = profile[2] & 255;
      double model = BodyFatModel.unadjusted(stableKg, height, sex, lowSegments),
          baselineModel = model,
          anchor = model;
      String baselineSession = session;
      try (android.database.Cursor c =
          db.rawQuery(
              "SELECT session_id,assumed_percent,model_percent,height_cm,sex FROM model_baselines"
                  + " WHERE id=?",
              new String[] {BodyFatModel.VERSION})) {
        if (c.moveToFirst()) {
          if (c.getInt(3) != height || c.getInt(4) != sex) {
            a.say("Profile changed. Choose a new reference scan in body-fat tuning.");
            baselineModel = model;
            anchor = model;
          } else {
            baselineSession = c.getString(0);
            anchor = c.getDouble(1);
            baselineModel = c.getDouble(2);
          }
        }
      }
      double value = BodyFatModel.anchored(model, baselineModel, anchor);
      db.beginTransaction();
      try {
        db.execSQL(
            "INSERT OR IGNORE INTO model_baselines VALUES(?,?,?,?,?,?,?,?)",
            new Object[] {
              BodyFatModel.VERSION,
              session,
              model,
              model,
              height,
              sex,
              now,
              "Uncalibrated model baseline; no independent reference measurement"
            });
        org.json.JSONObject meta = new org.json.JSONObject();
        meta.put("session_id", session);
        meta.put("baseline_session_id", baselineSession);
        meta.put("assumed_baseline_percent", anchor);
        meta.put("unadjusted_model_percent", model);
        meta.put("baseline_model_percent", baselineModel);
        meta.put("validated", false);
        meta.put("height_cm", height);
        meta.put("sex_code", sex);
        meta.put("provenance", "Experimental model; personal tuning is not measured calibration");
        db.execSQL(
            "INSERT OR REPLACE INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
            new Object[] {
              "ble:" + session + ":body_fat",
              now,
              "ownscale_experimental",
              session,
              "body_fat",
              value,
              "percent",
              BodyFatModel.VERSION,
              meta.toString(),
              now
            });
        db.setTransactionSuccessful();
        estimated = true;
      } finally {
        db.endTransaction();
      }
      a.refresh();
    } catch (Exception e) {
      a.say("Body-fat estimate skipped: " + e.getMessage() + ". Raw readings retained.");
    }
  }

  void save(String metric, double value, String unit, String method, String now) {
    db.execSQL(
        "INSERT OR REPLACE INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)",
        new Object[] {
          "ble:" + session + ":" + metric,
          now,
          "hume_ble",
          session,
          metric,
          value,
          unit,
          method,
          "{\"session_id\":\"" + session + "\",\"validated\":false}",
          now
        });
  }

  void stop(String reason) {
    generation++;
    h.removeCallbacksAndMessages(null);
    a.captureActive(false);
    if (session != null && low && high) estimate(Instant.now().toString());
    if (scanning && scanner != null) scanner.stopScan(scan);
    scanning = false;
    if (gatt != null) {
      BluetoothGatt old = gatt;
      gatt = null;
      old.disconnect();
      old.close();
    }
    if (session != null) {
      db.execSQL(
          "UPDATE capture_sessions SET ended_at=?,status=? WHERE id=?",
          new Object[] {Instant.now().toString(), reason, session});
      session = null;
    }
    a.say(reason);
    a.refresh();
    a.captureFinished();
  }
}

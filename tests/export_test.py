"""Synthetic fixture only: verifies portable desktop reconstruction and preservation."""
import csv, json, math, pathlib, sqlite3, subprocess, sys, tempfile
with tempfile.TemporaryDirectory() as tmp:
    root=pathlib.Path(tmp);db=root/'fixture.db';c=sqlite3.connect(db)
    c.executescript('''
    PRAGMA user_version=2;
    CREATE TABLE observations(id TEXT PRIMARY KEY,measured_at TEXT,source TEXT,source_record_id TEXT,metric TEXT,value REAL,unit TEXT,method TEXT,metadata_json TEXT,imported_at TEXT);
    CREATE TABLE calibrations(id TEXT,baseline_session TEXT,anchor REAL,model REAL,sensitivity REAL,height_cm INTEGER,sex INTEGER,fat_fraction REAL);
    CREATE TABLE app_config(key TEXT,value TEXT);
    INSERT INTO calibrations VALUES('fixture','reference',20,25,1,175,1,0.75);
    INSERT INTO app_config VALUES('calibration','fixture');
    INSERT INTO app_config VALUES('bf_response_hours','0');
    ''')
    def observation(id,time,source,record,metric,value,meta={}):
        c.execute('INSERT INTO observations VALUES(?,?,?,?,?,?,?,?,?,?)',(id,time,source,record,metric,value,'kg' if metric=='weight' else 'percent','synthetic',json.dumps(meta),time))
    observation('w1','2024-01-01T08:00:00Z','hume','legacy','weight',90)
    observation('f1','2024-01-01T08:00:00Z','hume','legacy','body_fat',12)
    observation('w2','2024-02-01T08:00:00Z','hume_ble','reference','weight',80)
    observation('f2','2024-02-01T08:00:00Z','ownscale_experimental','reference','body_fat',25,{'unadjusted_model_percent':25,'height_cm':175,'sex_code':1})
    c.commit();c.close()
    subprocess.run([sys.executable,'tools/export.py',str(db),'--out',str(root/'out')],check=True,capture_output=True)
    rows=list(csv.DictReader((root/'out/measurements.csv').open()));h=next(r for r in rows if r['source']=='Hume')
    assert math.isclose(float(h['body_fat_percent']),100*23.5/90)
    assert float(h['body_fat_original_percent'])==12
    assert float(h['lookback_fat_fraction'])==.75
    backup=json.loads((root/'out/ownscale.json').read_text());assert len(backup['observations'])==4
    print('PASS: synthetic desktop reconstruction, original values and backup')

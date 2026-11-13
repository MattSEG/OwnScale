#!/usr/bin/env python3
"""Export raw observations, the adjusted measurement view, and a restorable backup."""
import argparse,csv,json,sqlite3,pathlib,datetime,math
p=argparse.ArgumentParser();p.add_argument('database',type=pathlib.Path);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=True)
c=sqlite3.connect(a.database.resolve().as_uri()+'?mode=ro',uri=True);c.row_factory=sqlite3.Row
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
rows=[dict(r) for r in c.execute('select * from observations order by measured_at desc,id')]
with (a.out/'observations.csv').open('w',newline='') as f:
    fields=[r[1] for r in c.execute('pragma table_info(observations)')]
    w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
payload={'format':'ownscale-backup','schema_version':c.execute('pragma user_version').fetchone()[0],'exported_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'observations':rows}
for name in ('capture_sessions','raw_packets','model_baselines','calibrations','annotations','app_config'):
    if c.execute('select 1 from sqlite_master where type=? and name=?',('table',name)).fetchone():
        payload[name]=[dict(r) for r in c.execute('select * from '+name)]
(a.out/'ownscale.json').write_text(json.dumps(payload,indent=2)+'\n')
config={r['key']:r['value'] for r in payload.get('app_config',[])}
cal=next((r for r in payload.get('calibrations',[]) if r['id']==config.get('calibration','original')),None)
notes={r['reading_key']:r for r in payload.get('annotations',[])}
measurements={}
models={}
for r in rows:
    local=r['source'] in ('hume_ble','ownscale_experimental')
    key=('scan:'+r['source_record_id']) if local else ('manual:'+r['source_record_id']) if r['source']=='manual' else 'hume:'+r['measured_at']
    m=measurements.setdefault(key,{'measured_at':r['measured_at'],'source':'OwnScale' if local else 'Manual' if r['source']=='manual' else 'Hume','weight_kg':'','body_fat_percent':'','body_fat_method':'','waist_cm':'','excluded':'false','note':'','reading_id':key,'calibration_id':'','body_fat_original_percent':'','lookback_fat_fraction':''})
    if r['metric']=='weight':m['weight_kg']=r['value']
    elif r['metric']=='waist':m['waist_cm']=r['value']
    elif r['metric']=='body_fat':
        m['body_fat_original_percent']=r['value'];m['body_fat_percent']=r['value'];m['body_fat_method']='experimental_personal_adjustment' if local else 'hume_reported_estimate'
        if local:
            try:
                meta=json.loads(r['metadata_json']);model=meta['unadjusted_model_percent'];models[key]=model
                if cal:
                    m['calibration_id']=cal['id'];v=cal['anchor']+cal['sensitivity']*(model-cal['model'])
                    m['body_fat_percent']=v if cal['height_cm']==meta['height_cm'] and cal['sex']==meta['sex_code'] and math.isfinite(v) and 0<v<100 else ''
            except (KeyError,ValueError,TypeError):m['body_fat_percent']=''
reference_weight=measurements.get('scan:'+cal['baseline_session'],{}).get('weight_kg','') if cal else ''
fat_fraction=float(cal.get('fat_fraction',.75)) if cal else .75
def lookback(weight):
    if not cal or weight=='' or reference_weight=='' or not all(math.isfinite(v) for v in [weight,reference_weight,cal['anchor'],fat_fraction]) or weight<=0 or reference_weight<=0 or not 0<cal['anchor']<100 or not 0<=fat_fraction<=1:return ''
    value=100*(reference_weight*cal['anchor']/100+fat_fraction*(weight-reference_weight))/weight
    return value if 0<value<100 else ''
for key,m in measurements.items():
    if m['source']=='Hume' and m['body_fat_original_percent']!='' and cal:
        m['body_fat_percent']=lookback(m['weight_kg']);m['body_fat_method']='hume_weight_reconstruction';m['calibration_id']=cal['id'];m['lookback_fat_fraction']=fat_fraction
    n=notes.get(key,{})
    m['excluded']=str(bool(n.get('excluded',0))).lower();m['note']=n.get('note','')
    if m['note'].lstrip().startswith(('=','+','-','@')):m['note']="'"+m['note']
response=float(config.get('bf_response_hours',72))
if not math.isfinite(response) or response<0:response=72
for m in measurements.values():m['body_fat_individual_percent']=m['body_fat_percent']
def millis(text):
    dt=datetime.datetime.fromisoformat(text.replace('Z','+00:00'));return int(dt.timestamp())*1000+dt.microsecond//1000
def filtered(sequence,values):
    if not sequence:return []
    previous=millis(sequence[0]['measured_at']);value=values[0];out=[]
    for row,raw in zip(sequence,values):
        now=millis(row['measured_at']);alpha=-math.expm1(-(now-previous)/(response*3600000));value+=alpha*(raw-value);out.append(value);previous=now
    return out
if response>0 and cal:
    own=sorted((m for m in measurements.values() if m['source']=='OwnScale' and m['body_fat_percent']!='' and m['excluded']=='false'),key=lambda m:millis(m['measured_at']))
    values=filtered(own,[models[m['reading_id']] for m in own]);reference=cal['model']
    for m,v in zip(own,values):
        if m['reading_id']=='scan:'+cal['baseline_session']:reference=v
    for m,v in zip(own,values):
        estimate=cal['anchor']+cal['sensitivity']*(v-reference);m['body_fat_percent']=estimate if 0<estimate<100 else ''
    legacy=sorted((m for m in measurements.values() if m['body_fat_method']=='hume_weight_reconstruction' and m['body_fat_percent']!='' and m['excluded']=='false'),key=lambda m:millis(m['measured_at']))
    for m,v in zip(legacy,filtered(legacy,[m['weight_kg'] for m in legacy])):m['body_fat_percent']=lookback(v)
    for m in measurements.values():
        if m['body_fat_method']=='experimental_personal_adjustment':m['body_fat_method']='experimental_personal_time_stabilized'
        elif m['body_fat_method']=='hume_weight_reconstruction':m['body_fat_method']='hume_weight_reconstruction_time_stabilized'
        if m['excluded']=='true':m['body_fat_percent']=''
with (a.out/'measurements.csv').open('w',newline='') as f:
    fields=['measured_at','source','weight_kg','body_fat_percent','body_fat_individual_percent','body_fat_method','waist_cm','excluded','note','reading_id','calibration_id','body_fat_original_percent','lookback_fat_fraction']
    w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(measurements.values())
print(f'Exported {len(rows)} observations / {len(measurements)} measurements to {a.out}')

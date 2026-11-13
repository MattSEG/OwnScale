#!/usr/bin/env python3
"""Fail CI for high-risk tracked artifacts. This supplements manual privacy review."""
import pathlib, re, subprocess
paths=subprocess.check_output(['git','ls-files','-z']).decode().split('\0')
errors=[]
for name in filter(None,paths):
    p=pathlib.Path(name)
    if any(part in {'private','build','__pycache__'} for part in p.parts) or p.suffix.lower() in {'.db','.sqlite','.sqlite3','.csv','.apk','.aab','.keystore','.jks','.log','.idsig'}:
        errors.append(name+': private/build artifact')
    if not p.is_file(): continue
    try: text=p.read_text()
    except UnicodeDecodeError: continue
    # Match secrets, not the deliberately generic local-development key password.
    if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}',text):errors.append(name+': potential credential')
    if re.search(r'/(?:Users|home)/[^/\s]+/',text):errors.append(name+': machine-specific home path')
if errors:raise SystemExit('\n'.join(errors))
print('PASS: tracked tree artifact/path/credential checks')

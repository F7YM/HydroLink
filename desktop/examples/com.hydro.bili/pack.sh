#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
node --test server.test.js web/login.test.cjs
node --check web/login.js
python3 - <<'PY'
from pathlib import Path
import json
import zipfile

root = Path('.')
manifest = json.loads((root / 'manifest.json').read_text())
required = ['manifest.json', 'server.js', 'assets/icon.png', 'assets/slice.wasm', 'web/index.html', 'web/login.js', 'web/qrcode.min.js']
for name in required:
    if not (root / name).is_file():
        raise SystemExit('缺少打包文件: ' + name)
assert len(manifest['apps']) == 1 and not manifest.get('widgets')
files = [root / 'manifest.json', root / 'server.js']
for directory in ('assets', 'web'):
    files.extend(p for p in (root / directory).rglob('*') if p.is_file() and not p.name.endswith('.test.cjs'))
output = Path('..') / 'com.hydro.bili.hpk'
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(files):
        archive.write(file, file.as_posix())
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    assert set(required).issubset(archive.namelist())
print(f'已打包: {output} ({output.stat().st_size} bytes)')
PY

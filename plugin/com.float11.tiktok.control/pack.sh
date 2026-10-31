#!/usr/bin/env bash
# 打包抖音控制器插件为 HydroPlugin 安装包（.hpk）
# 用法: bash pack.sh
set -e
cd "$(dirname "$0")"

echo "== package .hpk =="
python3 - <<'PY'
import zipfile, os

out = '../com.float11.tiktok.control.hpk'
files = ['manifest.json', 'server.js']
for d in ('assets',):
    for root, _dirs, names in os.walk(d):
        for n in names:
            files.append(os.path.join(root, n))

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(files):
        z.write(f, f)

print('打包完成:', out, os.path.getsize(out), 'bytes')
PY

#!/usr/bin/env bash
# 打包 HydroChat 插件为 HydroPlugin 安装包（.hpk）
# 用法: bash pack.sh
set -e
cd "$(dirname "$0")"

echo "== package .hpk =="
python3 - <<'PY'
import zipfile, os

out = '../com.hydro.chat.hpk'
files = ['manifest.json', 'server.js']
for d in ('assets', 'web'):
    if os.path.exists(d):
        for root, _dirs, names in os.walk(d):
            for n in names:
                files.append(os.path.join(root, n))

with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(files):
        print('  +', f)
        z.write(f, f)

print('打包完成:', out, os.path.getsize(out), 'bytes')
PY

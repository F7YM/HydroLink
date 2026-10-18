#!/usr/bin/env bash
# 打包桌搭插件为 HydroPlugin 安装包（.hpk）
# 用法: bash pack.sh
set -e
cd "$(dirname "$0")"

echo "== test =="
node --test server.test.js

echo "== package .hpk =="
python3 - <<'PY'
import zipfile, os
out = '../com.hydro.deskbuddy.hpk'
files = ['manifest.json', 'server.js']
# web/ 是插件 WebUI，assets/ 是清单里声明的图标——都要进包，
# 少一个就会出现"页面打不开"或"图标 404"
for d in ('web', 'assets'):
    for root, _dirs, names in os.walk(d):
        for n in names:
            files.append(os.path.join(root, n))
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(f, f)
print('打包完成:', out, os.path.getsize(out), 'bytes')
PY

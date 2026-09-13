#!/usr/bin/env bash
# 构建桌搭插件并打包为 HydroPlugin 安装包（.hpk）
# 用法: bash pack.sh          # 编译 wasm + 打包
#       bash pack.sh --test   # 先跑 Rust 单测再打包
set -e
cd "$(dirname "$0")"

if [ "$1" = "--test" ]; then
  echo "== cargo test =="
  cargo test
fi

echo "== cargo build (wasm32-unknown-unknown) =="
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/hydro_deskbuddy.wasm plugin.wasm

echo "== package .hpk =="
python3 - <<'PY'
import zipfile, os
out = '../com.hydro.deskbuddy.hpk'
files = ['manifest.json', 'plugin.wasm']
for root, _dirs, names in os.walk('web'):
    for n in names:
        files.append(os.path.join(root, n))
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(f, f)
print('打包完成:', out, os.path.getsize(out), 'bytes')
PY

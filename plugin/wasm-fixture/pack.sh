#!/usr/bin/env bash
# 编译 WASM 夹具并打成可安装的 .hpk（跨平台测试与最小示例都用它）
set -e
cd "$(dirname "$0")"
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/hydro_wasm_fixture.wasm plugin.wasm
python3 - <<'PY'
import zipfile, os
out = '../com.hydro.wasmfixture.hpk'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in ('manifest.json', 'plugin.wasm'):
        z.write(f, f)
print('打包完成:', out, os.path.getsize(out), 'bytes')
PY

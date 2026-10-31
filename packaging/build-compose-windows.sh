#!/usr/bin/env bash
# 构建基于 Compose 运行时的 Windows 安装包准备目录 (packaging/build/compose-windows)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/packaging/build/compose-windows"
WINDOWS_DIR="$ROOT/packaging/windows"

mkdir -p "$STAGE"

echo "== 1. 确保 Windows 图标与版本资源 (.syso) 就绪 =="
if [ ! -f "$WINDOWS_DIR/rsrc_windows_amd64.syso" ]; then
    echo "!! 缺少 packaging/windows/rsrc_windows_amd64.syso（Windows 图标与版本资源）" >&2
    exit 1
fi

echo "== 2. 编译 Windows 专属启动引导器 (包含嵌入图标与 Java 环境智能探测) =="
cd "$WINDOWS_DIR"
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H=windowsgui" -o "$STAGE/HydroLink.exe" compose-launcher.go

echo "== 3. 校验并同步 Compose 应用 Jar 库 =="
APP_SOURCE="$ROOT/desktop-compose/build/compose/binaries/main/app/HydroLink/lib/app"
if [ -d "$APP_SOURCE" ]; then
    mkdir -p "$STAGE/lib/app"
    rsync -a --delete --exclude="*.so" --exclude="*.so.*" "$APP_SOURCE/" "$STAGE/lib/app/" 2>/dev/null \
        || cp -a "$APP_SOURCE/." "$STAGE/lib/app/"
fi

echo "== 4. 解出 skiko 原生库（skiko.library.path 指向 lib/app，缺 dll = 启动就炸）=="
# 启动器给 java 传 `-Dskiko.library.path=<安装目录>/lib/app`，skiko 就**只**从这里找
# skiko-windows-x64.dll —— 找不到直接 UnsatisfiedLinkError（真机表现就是「HydroLink 启动异常：
# Can't load library: …\lib\app\skiko-windows-x64.dll」）。dll 一直只在 jar 里，
# 所以这里解出来；.sha256 与 icudtl.dat 一并放好。
python3 - "$STAGE/lib/app" <<'PY'
import glob, os, sys, zipfile
out = sys.argv[1]
jars = sorted(glob.glob(os.path.join(out, 'skiko-awt-runtime-windows-x64-*.jar')))
if not jars:
    print('!! 找不到 skiko-awt-runtime-windows-x64 jar：Windows 端会起不来')
    sys.exit(0)
z = zipfile.ZipFile(jars[-1])
for name in ('skiko-windows-x64.dll', 'skiko-windows-x64.dll.sha256', 'icudtl.dat'):
    if name not in z.namelist():
        continue
    with z.open(name) as src, open(os.path.join(out, name), 'wb') as f:
        f.write(src.read())
    print('   解出', name)
PY
# Linux 侧原生库是打包机带过来的死重，Windows 包里不要
rm -f "$STAGE/lib/app"/libskiko-linux-x64.so* "$STAGE/lib/app"/skiko-awt-runtime-linux-x64*.jar

echo "== 5. 打包 Java 运行时（自包含：目标机器不必装 Java）=="
RUNTIME="$STAGE/lib/runtime"
if [ -f "$RUNTIME/bin/javaw.exe" ]; then
    echo "已有内嵌运行时：$RUNTIME/bin/javaw.exe（跳过下载）"
else
    # 应用要求 JVM 21（desktop-compose 的 jvmToolchain(21)）。启动器第一优先级就是
    # lib/runtime/bin/javaw.exe，没有才会去 JAVA_HOME / PATH 里探测——所以把 JRE 打进去，
    # 安装包才能在"干净的 Windows"上直接跑起来。
    # 这里用 Azul Zulu 的 Windows x64 JRE：**不走 GitHub**（Adoptium 的下载会 302 到
    # github.com，在连不上 GitHub 的网络里直接失败），Zulu 的包挂在 cdn.azul.com。
    JRE_URL="$(curl -s -m 30 'https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=windows&arch=x64&java_package_type=jre&archive_type=zip&javafx_bundled=false&latest=true&release_status=ga&page_size=1' \
        | python3 -c 'import json,sys
d = json.load(sys.stdin)
p = d[0] if isinstance(d, list) else d["packages"][0]
print(p["download_url"])' 2>/dev/null)"
    if [ -z "$JRE_URL" ]; then
        echo "!! 拿不到 JRE 下载地址（api.azul.com 不可达？）"
        echo "!! 安装包仍会生成，但目标机器需要自装 Java 21"
    else
        JRE_ZIP="$(mktemp --suffix=.zip)"
        echo "下载内嵌运行时: $JRE_URL"
        curl -s -m 900 -o "$JRE_ZIP" "$JRE_URL"
        python3 - "$JRE_ZIP" "$RUNTIME" <<'PY'
import os, shutil, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
root = names[0].split('/')[0]
out = sys.argv[2]
os.makedirs(out, exist_ok=True)
for n in names:
    rel = n[len(root) + 1:] if n.startswith(root + '/') else n
    if not rel:
        continue
    dst = os.path.join(out, rel)
    if n.endswith('/'):
        os.makedirs(dst, exist_ok=True)
        continue
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with z.open(n) as src, open(dst, 'wb') as f:
        shutil.copyfileobj(src, f)
PY
        rm -f "$JRE_ZIP"
        echo "内嵌运行时就绪: $RUNTIME/bin/javaw.exe"
    fi
fi

echo "== 6. 拷贝许可证与清理不需要的文件 (严格不打包 README) =="
cp "$ROOT/LICENSE" "$STAGE/LICENSE"
rm -f "$STAGE/README.md" "$STAGE"/*.md

echo "== 完成！compose-windows 目录已准备就绪 =="
echo "主程序：$STAGE/HydroLink.exe ($(du -h "$STAGE/HydroLink.exe" | cut -f1))"
echo "使用 Inno Setup 运行 packaging/windows/hydrolink-compose.iss 即可生成安装包。"

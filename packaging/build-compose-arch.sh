#!/usr/bin/env bash
# 构建 Compose 桌面端的 Arch Linux 安装包 (.pkg.tar.zst)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
STAGE="$ROOT/packaging/build/compose-stage"
WORK="$ROOT/packaging/build/compose-arch"
VERSION="1.3.0"
PKG="hydrolink"
MAINTAINER="float11 <2471825164@qq.com>"

mkdir -p "$DIST"
rm -rf "$STAGE" "$WORK"
mkdir -p "$STAGE" "$WORK"

# 1. 从刚才 gradle 生成的 packageDistributionForCurrentOS (app 镜像) 拷贝运行时
APP_DIR="$ROOT/desktop-compose/build/compose/binaries/main/app/HydroLink"
if [ ! -d "$APP_DIR" ]; then
    echo "错误：找不到 Compose 应用目录 $APP_DIR"
    exit 1
fi

mkdir -p "$STAGE/opt/hydrolink"
cp -a "$APP_DIR/." "$STAGE/opt/hydrolink/"

# 2. 创建 /usr/bin/hydrolink 软链接
mkdir -p "$STAGE/usr/bin"
ln -sf /opt/hydrolink/bin/HydroLink "$STAGE/usr/bin/$PKG"

# 3. 创建桌面图标和 .desktop
mkdir -p "$STAGE/usr/share/applications"
cat << 'DESKTOP' > "$STAGE/usr/share/applications/hydrolink.desktop"
[Desktop Entry]
Name=HydroLink
Comment=HydroLink 桌面端 (Compose)
Exec=/opt/hydrolink/bin/HydroLink
Icon=hydrolink
Terminal=false
Type=Application
Categories=Utility;Network;
DESKTOP

# 拷贝各尺寸应用图标
for f in "$ROOT"/packaging/build/icons/hydrolink-*.png; do
  size="$(basename "$f" | sed 's/hydrolink-//; s/\.png//')"
  mkdir -p "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps"
  install -m 0644 "$f" "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps/$PKG.png"
done

# 4. 拷贝到工作目录并生成 Arch 元数据
cp -a "$STAGE/." "$WORK/"

# .INSTALL 脚本
cat << 'INST' > "$WORK/.INSTALL"
post_install() {
    update-desktop-database -q || true
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
}

post_upgrade() {
    post_install
}

post_remove() {
    post_install
}
INST
chmod 0644 "$WORK/.INSTALL"

# 生成 .PKGINFO 和 .MTREE
python3 - "$WORK" "$VERSION" "$MAINTAINER" <<'PY'
import gzip, hashlib, os, stat, sys, time

stage, version, maintainer = sys.argv[1], sys.argv[2], sys.argv[3]
now = int(time.time())

total = 0
for root, _dirs, names in os.walk(stage):
    for n in names:
        total += os.lstat(os.path.join(root, n)).st_size

open(os.path.join(stage, ".PKGINFO"), "w", encoding="utf-8").write(f"""\
pkgname = hydrolink
pkgbase = hydrolink
pkgver = {version}-1
pkgdesc = HydroLink 桌面端 (Compose Multiplatform)
builddate = {now}
packager = {maintainer}
size = {total}
arch = x86_64
license = GPL-3.0-only
depend = glibc
depend = libx11
depend = webkit2gtk-4.1
xdata = pkgtype=pkg
""")

lines = ["#mtree", f"./ type=dir uid=0 gid=0 mode=0755 time={now}.0"]
for root, dirs, names in os.walk(stage):
    dirs.sort()
    rel = os.path.relpath(root, stage)
    if rel != ".":
        st = os.lstat(root)
        lines.append(f"./{rel.replace(os.sep, '/')} type=dir uid=0 gid=0 "
                     f"mode={stat.S_IMODE(st.st_mode):04o} time={now}.0")
    for n in sorted(names):
        fp = os.path.join(root, n)
        p = os.path.join(rel, n).replace(os.sep, "/")
        if p.startswith("./"):
            p = p[2:]
        if os.path.islink(fp):
            target = os.readlink(fp)
            lines.append(f"./{p} type=link link={target} time={now}.0")
        else:
            st = os.lstat(fp)
            data = open(fp, "rb").read()
            lines.append(f"./{p} type=file uid=0 gid=0 mode={stat.S_IMODE(st.st_mode):04o} "
                         f"size={st.st_size} md5digest={hashlib.md5(data).hexdigest()} "
                         f"sha256digest={hashlib.sha256(data).hexdigest()} time={now}.0")

with gzip.open(os.path.join(stage, ".MTREE"), "wb", compresslevel=9) as f:
    f.write(("\n".join(lines) + "\n").encode("utf-8"))

print(f"Arch PKGINFO/.MTREE 已生成：安装占用 {total} 字节，清单 {len(lines) - 1} 项")
PY

OUT="$DIST/${PKG}-${VERSION}-1-x86_64.pkg.tar.zst"
tar -C "$WORK" --zstd -cf "$OUT" .PKGINFO .MTREE .INSTALL opt usr
echo "-> dist/$(basename "$OUT")"

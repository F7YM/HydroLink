#!/usr/bin/env bash
# 把已装好的文件树打成一个 Arch 包（.pkg.tar.zst）。
#
# 为什么手搓：构建机是 Fedora，装不了 makepkg/pacman。Arch 包的格式本身很简单：
#
#     hydrolink-<ver>-1-x86_64.pkg.tar.zst
#     ├── .PKGINFO    元数据（依赖/版本/安装占用）
#     ├── .MTREE      gzip 过的文件清单（pacman 用它做校验）
#     ├── .INSTALL    安装脚本（刷新桌面库与图标缓存）
#     └── usr/…       文件树（路径**不带** ./ 前缀，与 makepkg 产出一致）
#
# 用法：bash packaging/build-arch-package.sh <stage 目录> <版本> <打包者>
set -euo pipefail

STAGE="${1:?需要 stage 目录}"
VERSION="${2:?需要版本号}"
MAINTAINER="${3:-float11 <2471825164@qq.com>}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
PKG=hydrolink
WORK="$ROOT/packaging/build/arch"

command -v zstd >/dev/null || { echo "缺少 zstd，无法打 Arch 包"; exit 1; }

rm -rf "$WORK"; mkdir -p "$WORK"
cp -a "$STAGE/." "$WORK/"
install -m 0644 "$ROOT/packaging/arch/hydrolink.install" "$WORK/.INSTALL"

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
pkgdesc = 手表互联服务端与 HydroKit 插件平台
builddate = {now}
packager = {maintainer}
size = {total}
arch = x86_64
license = Apache-2.0
depend = webkit2gtk-4.1
depend = gtk3
depend = playerctl
depend = zenity
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
        st = os.lstat(fp)
        data = open(fp, "rb").read()
        p = os.path.join(rel, n).replace(os.sep, "/")
        if p.startswith("./"):
            p = p[2:]
        lines.append(f"./{p} type=file uid=0 gid=0 mode={stat.S_IMODE(st.st_mode):04o} "
                     f"size={st.st_size} md5digest={hashlib.md5(data).hexdigest()} "
                     f"sha256digest={hashlib.sha256(data).hexdigest()} time={now}.0")

with gzip.open(os.path.join(stage, ".MTREE"), "wb", compresslevel=9) as f:
    f.write(("\n".join(lines) + "\n").encode("utf-8"))

print(f"PKGINFO/.MTREE 已生成：安装占用 {total} 字节，清单 {len(lines) - 1} 项")
PY

mkdir -p "$DIST"
OUT="$DIST/${PKG}-${VERSION}-1-x86_64.pkg.tar.zst"
# 顺序与 makepkg 一致：先元数据再文件树；顶层显式列出，避免 tar 给路径加 ./ 前缀
tar -C "$WORK" --zstd -cf "$OUT" .PKGINFO .MTREE .INSTALL usr
echo "-> dist/$(basename "$OUT")"

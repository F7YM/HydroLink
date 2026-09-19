#!/usr/bin/env bash
# 构建 Linux 安装包（deb 与 rpm），不依赖第三方打包器：
#   · deb 用 dpkg-deb + DEBIAN/control
#   · rpm 用 rpmbuild + packaging/rpm/hydrolink.spec
#
# 用法：
#   bash packaging/build-linux-packages.sh            # deb + rpm
#   bash packaging/build-linux-packages.sh deb        # 只出 deb
#   bash packaging/build-linux-packages.sh rpm        # 只出 rpm
#
# 产物在 dist/。版本号来自 desktop/VERSION，并通过 -ldflags -X 注入二进制，
# 保证「包版本」与「程序自报版本（hydrolink -version）」永远一致。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESKTOP="$ROOT/desktop"
DIST="$ROOT/dist"
STAGE="$ROOT/packaging/build/stage"
VERSION="$(tr -d '[:space:]' < "$DESKTOP/VERSION")"
PKG=hydrolink
ARCH_DEB=amd64
ARCH_RPM=x86_64
MAINTAINER="float11 <2471825164@qq.com>"

WHAT="${1:-all}"

echo "== 版本 $VERSION / 架构 $ARCH_DEB($ARCH_RPM) =="

# ---- 1) 编译二进制（cgo + webkit2gtk-4.1）----
echo "== 编译 desktop =="
( cd "$DESKTOP" && go build -tags "desktop,production,webkit2_41" \
    -ldflags "-s -w -X hydrolink-desktop/internal/api.Version=$VERSION" \
    -o "$ROOT/packaging/build/hydrolink" . )
"$ROOT/packaging/build/hydrolink" -version

# ---- 2) 生成图标（源图 192px → hicolor 各尺寸）----
echo "== 生成图标 =="
python3 - "$DESKTOP/appicon.png" "$DESKTOP/icon-small.png" "$ROOT/packaging/build/icons" <<'PY'
import os, sys
from PIL import Image
src_path, small_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
src = Image.open(src_path).convert("RGBA")
# 小尺寸（托盘/窗口/菜单里的 16~48）用 icon-small.png，大尺寸仍用 appicon.png
small = Image.open(small_path).convert("RGBA") if os.path.exists(small_path) else src
for size in (16, 24, 32, 48, 64, 128, 192, 256):
    if size <= 48:
        small.resize((size, size), Image.LANCZOS).save(os.path.join(out, "hydrolink-%d.png" % size))
        continue
    img = src if src.width == size else src.resize((size, size), Image.LANCZOS)
    img.save(os.path.join(out, f"hydrolink-{size}.png"))
print("icons ->", out)
PY

# ---- 3) 公共文件树（deb 与 rpm 共用同一份内容）----
echo "== 准备文件树 =="
rm -rf "$STAGE"
mkdir -p "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" \
         "$STAGE/usr/share/metainfo" \
         "$STAGE/usr/share/doc/$PKG"
install -m 0755 "$ROOT/packaging/build/hydrolink" "$STAGE/usr/bin/$PKG"
install -m 0644 "$ROOT/packaging/common/hydrolink.desktop" "$STAGE/usr/share/applications/hydrolink.desktop"
install -m 0644 "$ROOT/packaging/common/com.hydro.hydrolink.metainfo.xml" "$STAGE/usr/share/metainfo/com.hydro.hydrolink.metainfo.xml"
install -m 0644 "$ROOT/LICENSE" "$STAGE/usr/share/doc/$PKG/LICENSE"
install -m 0644 "$ROOT/README.md" "$STAGE/usr/share/doc/$PKG/README.md" 2>/dev/null || true
for f in "$ROOT"/packaging/build/icons/hydrolink-*.png; do
  size="$(basename "$f" | sed 's/hydrolink-//; s/\.png//')"
  mkdir -p "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps"
  install -m 0644 "$f" "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps/$PKG.png"
done

# ---- 4) deb ----
if [ "$WHAT" = "all" ] || [ "$WHAT" = "deb" ]; then
  echo "== 构建 deb =="
  DEB="$ROOT/packaging/build/deb"
  rm -rf "$DEB"; mkdir -p "$DEB"
  cp -a "$STAGE/." "$DEB/"
  mkdir -p "$DEB/DEBIAN"

  # 依赖：全部硬依赖（用户要求开箱即用）。
  # libgtk-3-0 在 Ubuntu 24.04+/Debian trixie 改名为 libgtk-3-0t64（time_t 迁移），
  # 所以用「或」列出两个名字。
  sed -e "s|@PKG@|$PKG|g" \
      -e "s|@VERSION@|$VERSION|g" \
      -e "s|@ARCH@|$ARCH_DEB|g" \
      -e "s|@MAINTAINER@|$MAINTAINER|g" \
      -e "s|@SIZE@|$(du -sk "$DEB" | cut -f1)|g" \
      -e "s|@DEPENDS@|libwebkit2gtk-4.1-0, libgtk-3-0 \| libgtk-3-0t64, playerctl, zenity|g" \
      "$ROOT/packaging/deb/control.in" > "$DEB/DEBIAN/control"
  install -m 0755 "$ROOT/packaging/deb/postinst" "$DEB/DEBIAN/postinst"
  install -m 0755 "$ROOT/packaging/deb/postrm" "$DEB/DEBIAN/postrm"
  sed -e "s|@VERSION@|$VERSION|g" "$ROOT/packaging/deb/copyright.in" \
      > "$DEB/usr/share/doc/$PKG/copyright"

  mkdir -p "$DIST"
  dpkg-deb --root-owner-group -Zxz --build "$DEB" "$DIST/${PKG}_${VERSION}_${ARCH_DEB}.deb" >/dev/null
  echo "-> dist/${PKG}_${VERSION}_${ARCH_DEB}.deb"
fi

# ---- 5) rpm ----
if [ "$WHAT" = "all" ] || [ "$WHAT" = "rpm" ]; then
  echo "== 构建 rpm =="
  command -v rpmbuild >/dev/null || { echo "缺少 rpmbuild，跳过 rpm"; exit 0; }
  TOP="$ROOT/packaging/build/rpmbuild"
  rm -rf "$TOP"
  mkdir -p "$TOP"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}

  # spec 从「已装好的文件树」打二进制包：把 stage 打成 tar 放进 SOURCES，
  # spec 里只做 %install 拷贝 + %files 列表（不重新编译，避免 rpmbuild 里跑 Go）。
  tar -C "$STAGE" -czf "$TOP/SOURCES/hydrolink-$VERSION-files.tar.gz" .
  rpmbuild --define "_topdir $TOP" \
           --define "hydrolink_version $VERSION" \
           -bb "$ROOT/packaging/rpm/hydrolink.spec" 2>&1 | tail -5
  mkdir -p "$DIST"
  find "$TOP/RPMS" -name '*.rpm' -exec cp {} "$DIST/" \;
  ls -1 "$DIST"/*.rpm
fi

echo
echo "== 产物 =="
ls -la "$DIST" | sed 1d

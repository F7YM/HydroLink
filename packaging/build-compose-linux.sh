#!/usr/bin/env bash
# 构建基于 Compose 运行时的 Linux 安装包（deb / rpm / Arch）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
STAGE="$ROOT/packaging/build/compose-stage"
VERSION="1.2.3"
PKG="hydrolink"
ARCH_DEB="amd64"
ARCH_RPM="x86_64"
MAINTAINER="float11 <2471825164@qq.com>"

mkdir -p "$DIST"
rm -rf "$STAGE"
mkdir -p "$STAGE/opt/hydrolink" \
         "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" \
         "$STAGE/usr/share/metainfo" \
         "$STAGE/usr/share/doc/$PKG"

echo "== 1. 拷贝 Compose 应用运行时 =="
APP_DIR="$ROOT/desktop-compose/build/compose/binaries/main/app/HydroLink"
if [ ! -d "$APP_DIR" ]; then
    echo "错误：找不到 Compose 应用目录 $APP_DIR，请先运行 ./gradlew createDistributable"
    exit 1
fi
cp -a "$APP_DIR/." "$STAGE/opt/hydrolink/"

echo "== 2. 创建软链接与桌面文件 =="
ln -sf /opt/hydrolink/bin/HydroLink "$STAGE/usr/bin/$PKG"

install -m 0644 "$ROOT/packaging/common/hydrolink.desktop" "$STAGE/usr/share/applications/hydrolink.desktop"
install -m 0644 "$ROOT/packaging/common/com.hydro.hydrolink.metainfo.xml" "$STAGE/usr/share/metainfo/com.hydro.hydrolink.metainfo.xml"
install -m 0644 "$ROOT/LICENSE" "$STAGE/usr/share/doc/$PKG/LICENSE"
install -m 0644 "$ROOT/README.md" "$STAGE/usr/share/doc/$PKG/README.md" 2>/dev/null || true

# 拷贝图标
for f in "$ROOT"/packaging/build/icons/hydrolink-*.png; do
  size="$(basename "$f" | sed 's/hydrolink-//; s/\.png//')"
  mkdir -p "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps"
  install -m 0644 "$f" "$STAGE/usr/share/icons/hicolor/${size}x${size}/apps/$PKG.png"
done

echo "== 3. 构建 deb 包 =="
DEB="$ROOT/packaging/build/deb"
rm -rf "$DEB"; mkdir -p "$DEB"
cp -a "$STAGE/." "$DEB/"
mkdir -p "$DEB/DEBIAN"

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

dpkg-deb --root-owner-group -Zxz --build "$DEB" "$DIST/${PKG}_${VERSION}_${ARCH_DEB}.deb" >/dev/null
echo "-> dist/${PKG}_${VERSION}_${ARCH_DEB}.deb"

echo "== 4. 构建 rpm 包 =="
TOP="$ROOT/packaging/build/rpmbuild"
rm -rf "$TOP"
mkdir -p "$TOP"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,tmp}

tar -C "$STAGE" -czf "$TOP/SOURCES/hydrolink-$VERSION-files.tar.gz" .
rpmbuild --define "_topdir $TOP" \
         --define "_tmppath $TOP/tmp" \
         --define "hydrolink_version $VERSION" \
         -bb "$ROOT/packaging/rpm/hydrolink.spec" 2>&1 | tail -5
find "$TOP/RPMS" -name '*.rpm' -exec cp {} "$DIST/" \;
echo "-> dist/$(ls -1 "$TOP/RPMS"/*/*.rpm | xargs -n1 basename)"

echo "== 5. 构建 Arch 包 (.pkg.tar.zst) =="
bash "$ROOT/packaging/build-compose-arch.sh"

echo "== Linux 全平台 Compose 打包完成 =="
ls -lh "$DIST"/hydrolink*

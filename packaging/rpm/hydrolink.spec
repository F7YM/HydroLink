%{!?hydrolink_version:%global hydrolink_version 1.2.2}

# 二进制包：不生成 debuginfo/debugsource（没有编译步骤，自动生成会因空文件失败）
%global debug_package %{nil}

Name:           hydrolink
Version:        %{hydrolink_version}
Release:        1%{?dist}
Summary:        Watch companion server and HydroKit plugin platform
Summary(zh_CN): 手表互联服务端与 HydroKit 插件平台
License:        Apache-2.0
# URL 暂缺（仓库未公开），有地址后补上
BuildArch:      x86_64

# 说明：这个 spec 打的是**二进制包** —— 文件树由
# packaging/build-linux-packages.sh 预先编译好并按包内路径排好，打成
# hydrolink-<version>-files.tar.gz 作为 Source0；spec 里只做解包与拷贝。
# 这样 rpmbuild 不需要 Go 工具链与 webkit2gtk-devel。要出源码包（SRPM）时
# 再补 %prep/%build 里的编译步骤。
Source0:        hydrolink-%{version}-files.tar.gz

Requires:       webkit2gtk4.1
Requires:       gtk3
Requires:       playerctl
Requires:       zenity

%description
HydroLink is the desktop side of a watch + desktop setup. It serves the HTTP API
the watch app talks to (pairing, device status, music, commands, plugins) and
hosts the HydroKit plugin platform, so installed plugins can render their own
interfaces on the watch and expose a settings UI on the desktop.

手表互联服务端：为手表 App 提供配对/状态/音乐/命令/插件接口，并承载 HydroKit
插件平台（插件可在手表上渲染界面、在桌面端提供设置界面）。

%prep
%setup -q -c -n hydrolink-%{version}

%build
# 无编译步骤（二进制在 Source0 里，见顶部说明）

%install
rm -rf %{buildroot}
mkdir -p %{buildroot}
cp -a . %{buildroot}/

%files
%license %{_datadir}/doc/hydrolink/LICENSE
%doc %{_datadir}/doc/hydrolink/README.md
/opt/hydrolink
%{_bindir}/hydrolink
%{_datadir}/applications/hydrolink.desktop
%{_datadir}/metainfo/com.hydro.hydrolink.metainfo.xml
%{_datadir}/icons/hicolor/16x16/apps/hydrolink.png
%{_datadir}/icons/hicolor/24x24/apps/hydrolink.png
%{_datadir}/icons/hicolor/32x32/apps/hydrolink.png
%{_datadir}/icons/hicolor/48x48/apps/hydrolink.png
%{_datadir}/icons/hicolor/64x64/apps/hydrolink.png
%{_datadir}/icons/hicolor/128x128/apps/hydrolink.png
%{_datadir}/icons/hicolor/192x192/apps/hydrolink.png
%{_datadir}/icons/hicolor/256x256/apps/hydrolink.png

%post
# 刷新桌面数据库与图标缓存（缺工具则跳过）
if [ -x /usr/bin/update-desktop-database ]; then
  update-desktop-database -q %{_datadir}/applications || :
fi
if [ -x /usr/bin/gtk-update-icon-cache ]; then
  gtk-update-icon-cache -q -t -f %{_datadir}/icons/hicolor || :
fi

%postun
if [ -x /usr/bin/update-desktop-database ]; then
  update-desktop-database -q %{_datadir}/applications || :
fi

%changelog
* Sun Sep 13 2026 float11 <2471825164@qq.com> - %{hydrolink_version}-1
- Initial package

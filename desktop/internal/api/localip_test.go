package api

import (
	"net"
	"testing"
)

func cand(name, ip string, up bool, loopback bool) ipCandidate {
	return ipCandidate{Name: name, IP: net.ParseIP(ip).To4(), Up: up, Loopback: loopback}
}

// 关键回归：Windows 上 Hyper-V/WSL 的虚拟网卡常常排在真实网卡之前，
// 旧实现（第一个非回环 IPv4）会把配对地址给成 172.28.x，手表根本连不上。
func TestPickIPPrefersRealAdapter(t *testing.T) {
	cases := []struct {
		name      string
		defaultIP string
		cands     []ipCandidate
		want      string
		iface     string
		source    string
	}{
		{
			// 本机实测踩到的真实场景：Clash 系代理的 TUN 网卡占着默认路由，
			// 它的 198.18.0.1 是 fake-IP 段，手表连不上；必须回落到真实网卡。
			name:      "代理 TUN 占着默认路由（Clash/FlClash）",
			defaultIP: "198.18.0.1",
			cands: []ipCandidate{
				cand("FlClash", "198.18.0.1", true, false),
				cand("wlan0", "192.168.3.48", true, false),
			},
			want:   "192.168.3.48",
			iface:  "wlan0",
			source: "scored",
		},
		{
			name:      "默认路由可用时优先它",
			defaultIP: "192.168.3.48",
			cands: []ipCandidate{
				cand("Wi-Fi", "192.168.3.48", true, false),
				cand("vEthernet (WSL)", "172.28.96.1", true, false),
			},
			want:   "192.168.3.48",
			iface:  "Wi-Fi",
			source: "default-route",
		},
		{
			name:      "默认路由是 CGNAT（Tailscale）时不用它",
			defaultIP: "100.101.102.103",
			cands: []ipCandidate{
				cand("tailscale0", "100.101.102.103", true, false),
				cand("enp3s0", "10.0.0.9", true, false),
			},
			want:   "10.0.0.9",
			iface:  "enp3s0",
			source: "scored",
		},
		{
			name: "windows: Hyper-V 虚拟网卡在前",
			cands: []ipCandidate{
				cand("vEthernet (WSL)", "172.28.96.1", true, false),
				cand("vEthernet (Default Switch)", "172.20.144.1", true, false),
				cand("Wi-Fi", "192.168.3.48", true, false),
			},
			want:  "192.168.3.48",
			iface: "Wi-Fi",
		},
		{
			name: "只有虚拟网卡时兜底用它（VPN 地址也比 127.0.0.1 有用）",
			cands: []ipCandidate{
				cand("vEthernet (Default Switch)", "172.20.144.1", true, false),
			},
			want:   "172.20.144.1",
			iface:  "vEthernet (Default Switch)",
			source: "last-resort",
		},
		{
			name: "macos: utun VPN 在前",
			cands: []ipCandidate{
				cand("utun4", "10.8.0.6", true, false),
				cand("en0", "192.168.1.24", true, false),
				cand("awdl0", "169.254.10.1", true, false),
			},
			want:  "192.168.1.24",
			iface: "en0",
		},
		{
			name: "linux: docker0 在前",
			cands: []ipCandidate{
				cand("docker0", "172.17.0.1", true, false),
				cand("virbr0", "192.168.122.1", true, false),
				cand("wlan0", "192.168.2.31", true, false),
			},
			want:  "192.168.2.31",
			iface: "wlan0",
		},
		{
			name: "以太网优先于公网地址",
			cands: []ipCandidate{
				cand("eth0", "203.0.113.7", true, false),
				cand("enp3s0", "10.0.0.9", true, false),
			},
			want:  "10.0.0.9",
			iface: "enp3s0",
		},
		{
			name: "跳过 down/loopback/链路本地（全不可用则回退 127.0.0.1）",
			cands: []ipCandidate{
				cand("lo", "127.0.0.1", true, true),
				cand("eth0", "192.168.5.5", false, false),
				cand("wlan0", "169.254.3.4", true, false),
			},
			want:   "127.0.0.1",
			iface:  "",
			source: "fallback",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			info := selectIP(tc.defaultIP, tc.cands)
			if info.IP != tc.want {
				t.Fatalf("selectIP = %q (%s, %s), 期望 %q (%s)",
					info.IP, info.Iface, info.Source, tc.want, tc.iface)
			}
			if info.Iface != tc.iface {
				t.Fatalf("选中网卡 = %q, 期望 %q", info.Iface, tc.iface)
			}
			if tc.source != "" && info.Source != tc.source {
				t.Fatalf("来源 = %q, 期望 %q", info.Source, tc.source)
			}
		})
	}
}

func TestIsVirtualIface(t *testing.T) {
	virtual := []string{
		"docker0", "veth1a2b", "virbr0", "br-abc123", "vmnet8", "vboxnet0",
		"vEthernet (WSL)", "vEthernet (Default Switch)", "utun4", "tun0", "tap0",
		"wg0", "tailscale0", "zt7xyz", "awdl0", "llw0", "podman0", "cni0",
	}
	for _, n := range virtual {
		if !isVirtualIface(n) {
			t.Errorf("%q 应判为虚拟网卡", n)
		}
	}
	real := []string{"wlan0", "wlp3s0", "en0", "enp3s0", "eth0", "Wi-Fi", "Ethernet"}
	for _, n := range real {
		if isVirtualIface(n) {
			t.Errorf("%q 不应判为虚拟网卡", n)
		}
	}
}

// 地址分类一次测清（原 TestIsPrivateV4 与 TestIsUsableLANIP 重叠，已合并）：
// 局域网地址 = 私网段，且排除回环/链路本地与代理/VPN 段。
func TestAddressClassification(t *testing.T) {
	lan := []string{"192.168.1.10", "10.1.2.3", "172.16.5.5", "172.31.0.9"}
	notLAN := []string{
		"172.15.0.1", "172.32.0.1", "8.8.8.8", "203.0.113.9",
		"198.18.0.1", "198.19.255.1", // Clash / sing-box fake-IP
		"100.64.0.1", "100.101.102.103", // CGNAT / Tailscale
		"127.0.0.1", "169.254.1.1", "0.0.0.0",
	}
	for _, s := range lan {
		ip := net.ParseIP(s)
		if !isPrivateV4(ip) || !isUsableLANIP(ip) {
			t.Errorf("%s 应判为可用局域网地址", s)
		}
	}
	for _, s := range notLAN {
		if isUsableLANIP(net.ParseIP(s)) {
			t.Errorf("%s 不应判为可用局域网地址", s)
		}
	}
	// 172.17.0.1 是 docker 私网：地址本身是私网，只是网卡会被判为虚拟。
	if !isPrivateV4(net.ParseIP("172.17.0.1")) {
		t.Error("172.17.0.1 应仍是私网地址")
	}
}

// 本机实网断言：返回的必须是可用的私网地址，而不是 docker/NAT 里那套。
func TestLocalIPIsUsable(t *testing.T) {
	info := LocalIPInfo()
	t.Logf("LocalIP = %s (iface=%s via %s)", info.IP, info.Iface, info.Source)
	ip := net.ParseIP(info.IP)
	if ip == nil {
		t.Fatalf("LocalIP 不是合法地址: %q", info.IP)
	}
	if ip.IsLoopback() {
		t.Log("本机没有可用网卡，回退 127.0.0.1（CI/容器环境可接受）")
		return
	}
	if !isPrivateV4(ip) {
		t.Logf("注意：选中了公网地址 %s（在某些网络下是正常的）", info.IP)
	}
	if isVirtualIface(info.Iface) {
		t.Fatalf("选中了虚拟网卡 %q (%s)", info.Iface, info.IP)
	}
	// 选中的地址必须真的挂在某个 up 的网卡上。
	if info.Iface == "" {
		t.Fatalf("未解析出网卡名 (%s)", info.IP)
	}
}

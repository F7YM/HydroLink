package api

import (
	"net"
	"strings"
)

// LocalIP returns the address the watch should use to reach this desktop.
//
// The old implementation returned the first non-loopback IPv4 address, which
// is wrong on any machine with virtual adapters (Hyper-V / WSL / Docker on
// Windows, VPN tunnels / bridges on macOS, docker0 / virbr0 on Linux): those
// interfaces usually come first and are unreachable from the phone/watch.
func LocalIP() string {
	info := LocalIPInfo()
	return info.IP
}

// IPInfo describes which address was selected and why — logged at startup so
// "the watch can't connect" is diagnosable from the app log.
type IPInfo struct {
	IP     string
	Iface  string
	Source string // "default-route" | "scored" | "fallback"
}

// LocalIPInfo selects the LAN address with interface detail.
func LocalIPInfo() IPInfo {
	return selectIP(defaultRouteIP(), interfaceCandidates())
}

// selectIP is the pure decision core (default-route probe result + interface
// snapshot in, chosen address out) so every platform's adapter zoo — Hyper-V,
// WSL, docker0, utun, Clash TUN — is unit-testable.
func selectIP(defaultIP string, cands []ipCandidate) IPInfo {
	// 1) Preferred: the address the default route would use, but only when it
	//    is a real LAN address on a non-virtual interface. Proxy/VPN TUN
	//    adapters (Clash & friends use 198.18.0.0/15 fake-IP) own the default
	//    route on many machines while being unreachable from the watch.
	if defaultIP != "" {
		ip := net.ParseIP(defaultIP)
		name := nameInCandidates(cands, defaultIP)
		if name == "" {
			name = ifaceNameForIP(defaultIP)
		}
		if isUsableLANIP(ip) && !isVirtualIface(name) {
			return IPInfo{IP: defaultIP, Iface: name, Source: "default-route"}
		}
	}
	// 2) Score the interface list (virtual adapters excluded).
	if ip, name := pickIP(cands); ip != "" {
		return IPInfo{IP: ip, Iface: name, Source: "scored"}
	}
	// 3) Nothing but virtual/tunnel interfaces: still report the best of them
	//    (a VPN address beats telling the user 127.0.0.1) but keep it visible
	//    in the log that this is a last resort.
	if ip, name := pickIPAny(cands); ip != "" {
		return IPInfo{IP: ip, Iface: name, Source: "last-resort"}
	}
	return IPInfo{IP: "127.0.0.1", Source: "fallback"}
}

// isUsableLANIP reports whether an address can plausibly be dialed by the
// watch: private range, and NOT one of the ranges proxies/VPNs hand out
// (198.18.0.0/15 benchmark fake-IP, 100.64.0.0/10 CGNAT/Tailscale).
func isUsableLANIP(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	if ip4.IsLoopback() || ip4.IsLinkLocalUnicast() || ip4.IsUnspecified() {
		return false
	}
	if ip4[0] == 198 && (ip4[1] == 18 || ip4[1] == 19) {
		return false // RFC 2544, Clash/sing-box fake-IP
	}
	if ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
		return false // CGNAT, Tailscale
	}
	return isPrivateV4(ip4)
}

// defaultRouteIP dials a public address over UDP to learn the source IP the
// kernel would use. Nothing is transmitted; failure just means "no default
// route" (offline machine, or all traffic forced through a proxy).
func defaultRouteIP() string {
	for _, target := range []string{"8.8.8.8:80", "1.1.1.1:80", "223.5.5.5:80"} {
		conn, err := net.Dial("udp4", target)
		if err != nil {
			continue
		}
		addr, ok := conn.LocalAddr().(*net.UDPAddr)
		_ = conn.Close()
		if !ok || addr.IP == nil {
			continue
		}
		ip := addr.IP.To4()
		if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
			continue
		}
		return ip.String()
	}
	return ""
}

// ipCandidate is one address on one interface, kept as plain data so the
// selection rules are unit-testable for every platform.
type ipCandidate struct {
	Name     string
	IP       net.IP
	Up       bool
	Loopback bool
}

// interfaceCandidates snapshots the local interfaces.
func interfaceCandidates() []ipCandidate {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []ipCandidate
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipnet.IP.To4()
			if ip4 == nil {
				continue
			}
			out = append(out, ipCandidate{
				Name:     iface.Name,
				IP:       ip4,
				Up:       iface.Flags&net.FlagUp != 0,
				Loopback: iface.Flags&net.FlagLoopback != 0 || ip4.IsLoopback(),
			})
		}
	}
	return out
}

// virtualIfaceMarkers are substrings of interface names that never lead to the
// watch: VM/container bridges, tunnels, VPNs and Apple's peer-to-peer links.
var virtualIfaceMarkers = []string{
	"docker", "veth", "virbr", "br-", "podman", "cni", "flannel", "kube",
	"vmnet", "vboxnet", "vEthernet", "hyper-v", "wsl", "vethernet",
	"utun", "tun", "tap", "wg", "nordlynx", "tailscale", "zerotier", "zt",
	"bridge", "awdl", "llw", "ap1", "dummy", "sit", "ip6tnl", "gre",
	// 代理/VPN 客户端自建的 TUN（Clash 系、sing-box、各类 VPN）
	"clash", "mihomo", "sing", "surge", "warp", "xray", "v2ray", "hysteria",
	"nekoray", "prox", "vpn", "wintun", "proton", "expressvpn",
}

// physicalIfaceMarkers are names that usually ARE the LAN link.
var physicalIfaceMarkers = []string{
	"wlan", "wlp", "wifi", "wi-fi", "wl", "en", "eth", "enp", "ens", "eno",
	"ethernet", "以太网", "无线",
}

func isVirtualIface(name string) bool {
	if name == "" {
		return false
	}
	lower := strings.ToLower(name)
	for _, m := range virtualIfaceMarkers {
		if strings.Contains(lower, strings.ToLower(m)) {
			return true
		}
	}
	return false
}

func looksPhysical(name string) bool {
	lower := strings.ToLower(name)
	for _, m := range physicalIfaceMarkers {
		if strings.HasPrefix(lower, m) || strings.Contains(lower, m) {
			return true
		}
	}
	return false
}

func isPrivateV4(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	switch {
	case ip4[0] == 10:
		return true
	case ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31:
		return true
	case ip4[0] == 192 && ip4[1] == 168:
		return true
	}
	return false
}

// pickIP applies the selection rules and returns the best (ip, iface).
// Ordering is fully deterministic: score first, then interface name.
func pickIP(cands []ipCandidate) (string, string) {
	return pick(cands, false)
}

// pickIPAny ignores the virtual-interface exclusion (last resort).
func pickIPAny(cands []ipCandidate) (string, string) {
	return pick(cands, true)
}

func pick(cands []ipCandidate, includeVirtual bool) (string, string) {
	bestScore := -1
	var best ipCandidate
	for _, c := range cands {
		if c.Loopback || !c.Up || c.IP == nil {
			continue
		}
		if c.IP.IsLinkLocalUnicast() || c.IP.IsUnspecified() || c.IP.IsMulticast() {
			continue
		}
		if !includeVirtual && isVirtualIface(c.Name) {
			continue
		}
		score := 0
		if isUsableLANIP(c.IP) {
			score += 40
		}
		if looksPhysical(c.Name) {
			score += 30
		}
		if c.IP.IsGlobalUnicast() {
			score += 5
		}
		if score > bestScore || (score == bestScore && c.Name < best.Name) {
			bestScore, best = score, c
		}
	}
	if bestScore < 0 {
		return "", ""
	}
	return best.IP.String(), best.Name
}

// nameInCandidates finds which interface owns an address within a snapshot
// (keeps selectIP hermetic for tests; the real caller passes the live list).
func nameInCandidates(cands []ipCandidate, ip string) string {
	for _, c := range cands {
		if c.IP != nil && c.IP.String() == ip {
			return c.Name
		}
	}
	return ""
}

// ifaceNameForIP maps an address back to its interface name.
func ifaceNameForIP(ip string) string {
	cands := interfaceCandidates()
	for _, c := range cands {
		if c.IP != nil && c.IP.String() == ip {
			return c.Name
		}
	}
	return ""
}

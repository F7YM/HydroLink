package tray

// 托盘菜单在 Linux(DBusMenu) 与 Windows(Shell_NotifyIcon) 上必须完全一致，
// 所以规格只写一份：各平台实现都从这里取，测试也断言它。

const (
	// MenuIDShow 是"显示主窗口"的命令 id。
	MenuIDShow int32 = 1
	// MenuIDSep 是分隔符（Windows 用 MF_SEPARATOR，DBus 用 type=separator）。
	MenuIDSep int32 = 2
	// MenuIDQuit 是"退出"的命令 id。
	MenuIDQuit int32 = 3
)

// MenuEntry 是菜单里的一项。
type MenuEntry struct {
	ID    int32
	Label string
	// Separator 为真时 Label 忽略。
	Separator bool
}

// MenuEntries 返回托盘右键菜单的完整规格。
func MenuEntries() []MenuEntry {
	return []MenuEntry{
		{ID: MenuIDShow, Label: "显示主窗口"},
		{ID: MenuIDSep, Separator: true},
		{ID: MenuIDQuit, Label: "退出"},
	}
}

// DefaultTitle / DefaultTooltip 统一两平台的提示文案。
const (
	DefaultTitle   = "HydroLink"
	DefaultTooltip = "HydroLink — 桌面服务端"
)

package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// AnotherInstanceRunning reports whether the given port is already served by
// another HydroLink instance.
//
// 用途：第二次启动时端口必然被占，这时应当安静退出（Wails 的单实例锁会把
// 请求转给已运行的实例），而不是弹一个"无法启动"的错误框吓用户；只有端口
// 被**别的程序**占用才值得报错。
func AnotherInstanceRunning(port int) bool {
	client := &http.Client{Timeout: 900 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/version", port))
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	var v struct {
		Version  string `json:"version"`
		APILevel int    `json:"api_level"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&v); err != nil {
		return false
	}
	// HydroLink 的 /api/version 一定带 api_level（其他 HTTP 服务几乎不可能）。
	return v.APILevel > 0
}

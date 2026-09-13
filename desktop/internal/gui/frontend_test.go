package gui

import (
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// 前端没有构建步骤也没有 JS 测试环境，漏一个 DOM 节点只会在运行时报 TypeError
// （曾导致二次确认弹窗整体不显示）。这个测试把"main.js 引用的 id"与
// "index.html 里存在的 id"对齐，属于最低成本的防回归。
var (
	// main.js 中的 $("x") / getElementById("x")
	reJSElementID = regexp.MustCompile(`(?:\$|getElementById)\(\s*"([A-Za-z0-9_-]+)"\s*\)`)
	// index.html 中的 id="x"（含模板字符串里动态生成的 id 也能被扫到）
	reHTMLID = regexp.MustCompile(`id="([A-Za-z0-9_-]+)"`)
	// 模板字符串中出现的动态 id（例如 `${p.id}` 生成的按钮 data-* 不是 id，无需处理）
	reTemplateID = regexp.MustCompile("id=\"([A-Za-z0-9_-]*)\\$\\{")
)

func readFrontend(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile("../../frontend/dist/" + name)
	if err != nil {
		t.Fatalf("read %s: %v", name, err)
	}
	return string(b)
}

func TestFrontendReferencedIDsExist(t *testing.T) {
	html := readFrontend(t, "index.html")
	js := readFrontend(t, "main.js")

	have := map[string]bool{}
	for _, m := range reHTMLID.FindAllStringSubmatch(html, -1) {
		have[m[1]] = true
	}
	// main.js 里动态拼出来的 id 不计入"必须存在于 index.html"
	dynamic := map[string]bool{}
	for _, m := range reTemplateID.FindAllStringSubmatch(js, -1) {
		dynamic[m[1]] = true
	}

	var missing []string
	for _, m := range reJSElementID.FindAllStringSubmatch(js, -1) {
		id := m[1]
		if have[id] || dynamic[id] {
			continue
		}
		missing = append(missing, id)
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Fatalf("main.js 引用了 index.html 中不存在的元素 id: %s\n"+
			"（这类漏节点会在运行时抛 TypeError，静默中断后续 DOM 操作）",
			strings.Join(missing, ", "))
	}
}

// 确认弹窗必须同时具备标题/正文/确认/取消四个节点：缺正文曾经让 showConfirm 崩掉，
// 弹窗不显示、卸载流程完全不可用。
func TestConfirmModalNodesPresent(t *testing.T) {
	html := readFrontend(t, "index.html")
	for _, id := range []string{"confirmModal", "confirmTitle", "confirmText", "confirmOk", "confirmCancel"} {
		if !strings.Contains(html, `id="`+id+`"`) {
			t.Errorf("二次确认弹窗缺少节点 %q", id)
		}
	}
}

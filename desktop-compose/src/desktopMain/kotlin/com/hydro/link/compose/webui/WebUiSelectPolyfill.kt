package com.hydro.link.compose.webui

/**
 * 插件 WebUI 的 HTML `<select>` 交互增强（Polyfill）。
 *
 * ## 为什么需要它
 *
 * 在 Linux（尤其是 Wayland / XWayland 环境）下，桌面客户端使用 `ca.weblite:webview` 的
 * 离屏模式（[WebViewLightweightComponent]）。在该模式下 WebKitGTK 将页面离屏绘制到
 * Cairo 内存画板上，宿主没有任何被窗口管理器 map / realize 的顶级原生 X11/Wayland GtkWindow。
 *
 * HTML 标准的 `<select>` 下拉框在 WebKitGTK 内部实现中是调用系统的 `GtkMenu` / `GtkPopover`
 * 弹出一个顶层原生浮动窗口，但在离屏模式下由于缺少底层顶级窗口上下文和 grab 机制，
 * 原生下拉菜单会静默失败，导致用户点击 `<select>` 下拉框时完全弹不开！
 *
 * ## 解决方案
 *
 * 通过注入这段现代化的交互增强脚本（Polyfill）：
 * 1. 在 `mousedown` 捕获阶段拦截对 `<select>` 的点击，阻止底层失效的原生呼起；
 * 2. 动态创建完全自适应（支持深色/浅色、圆角、阴影、选中对勾 `✓`、hover、禁用项与 optgroup）
 *    的悬浮菜单浮层；
 * 3. 选定选项后同步更新 `selectedIndex` 与 `value`，并主动派发原生标准的 `input` 和 `change` 事件
 *    （带 `bubbles: true`），完全透明地让插件的前端框架或原生监听器正常响应；
 * 4. 具备全局幂等防护（`__hk_select_polyfill_installed`），在内嵌系统 WebView 和外部浏览器中
 *    均能安全共存。
 */
object WebUiSelectPolyfill {

    const val SCRIPT: String = """
(function() {
  if (typeof window === "undefined" || window.__hk_select_polyfill_installed) return;
  window.__hk_select_polyfill_installed = true;

  var activeMenu = null;

  function closeMenu() {
    if (activeMenu) {
      if (activeMenu.parentNode) activeMenu.parentNode.removeChild(activeMenu);
      activeMenu = null;
    }
  }

  function getEffectiveTheme(el) {
    try {
      var cur = el;
      while (cur && cur !== document.documentElement && cur !== document.body) {
        var bg = window.getComputedStyle(cur).backgroundColor;
        if (bg && bg !== "transparent" && bg !== "rgba(0, 0, 0, 0)") {
          var m = bg.match(/\d+/g);
          if (m && m.length >= 3) {
            var lum = (Number(m[0]) * 299 + Number(m[1]) * 587 + Number(m[2]) * 114) / 1000;
            return lum < 128 ? "dark" : "light";
          }
        }
        cur = cur.parentElement;
      }
    } catch (e) {}
    try {
      if (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches) {
        return "light";
      }
    } catch (e) {}
    return "dark";
  }

  function showSelectMenu(select) {
    if (!select || select.disabled) return;
    if (activeMenu && activeMenu.__target === select) {
      closeMenu();
      return;
    }
    closeMenu();

    var rect = select.getBoundingClientRect();
    var isDark = getEffectiveTheme(select) === "dark";

    var menu = document.createElement("div");
    menu.className = "hk-select-overlay-menu";
    menu.__target = select;
    activeMenu = menu;

    var computed = window.getComputedStyle(select);
    var minW = Math.max(rect.width, 110);
    var fontSize = computed.fontSize || "14px";
    var fontFamily = computed.fontFamily || "system-ui, -apple-system, sans-serif";

    menu.style.cssText = [
      "position: absolute",
      "z-index: 2147483647",
      "box-sizing: border-box",
      "min-width: " + minW + "px",
      "max-height: 280px",
      "overflow-y: auto",
      "border-radius: 8px",
      "padding: 4px 0",
      "margin: 0",
      "font-size: " + fontSize,
      "font-family: " + fontFamily,
      "background-color: " + (isDark ? "#252528" : "#ffffff"),
      "color: " + (isDark ? "#f2f2f7" : "#1c1c1e"),
      "border: 1px solid " + (isDark ? "rgba(255, 255, 255, 0.16)" : "rgba(0, 0, 0, 0.12)"),
      "box-shadow: 0 10px 30px " + (isDark ? "rgba(0, 0, 0, 0.65)" : "rgba(0, 0, 0, 0.15)"),
      "user-select: none",
      "-webkit-user-select: none",
      "backdrop-filter: blur(20px)",
      "-webkit-backdrop-filter: blur(20px)"
    ].join(";");

    var top = rect.bottom + window.scrollY + 4;
    var left = rect.left + window.scrollX;
    var viewportH = window.innerHeight || document.documentElement.clientHeight || 600;
    var viewportW = window.innerWidth || document.documentElement.clientWidth || 800;

    if (rect.bottom + 220 > viewportH && rect.top > 180) {
      top = rect.top + window.scrollY - 4;
      menu.style.transform = "translateY(-100%)";
    }

    var maxLeft = viewportW - minW - 10;
    menu.style.top = Math.max(4, top) + "px";
    menu.style.left = Math.max(6, Math.min(left, maxLeft)) + "px";

    function renderOptions(container, elements) {
      for (var i = 0; i < elements.length; i++) {
        var el = elements[i];
        if (el.tagName === "OPTGROUP") {
          var grp = document.createElement("div");
          grp.textContent = el.label || "";
          grp.style.cssText = [
            "padding: 6px 12px 2px",
            "font-size: 11px",
            "font-weight: 600",
            "opacity: 0.5",
            "text-transform: uppercase"
          ].join(";");
          container.appendChild(grp);
          renderOptions(container, el.children);
          continue;
        }
        if (el.tagName !== "OPTION") continue;

        (function(opt) {
          var item = document.createElement("div");
          var isSelected = (opt.index === select.selectedIndex);

          item.style.cssText = [
            "padding: 7px 12px",
            "cursor: " + (opt.disabled ? "not-allowed" : "pointer"),
            "opacity: " + (opt.disabled ? "0.4" : "1"),
            "display: flex",
            "align-items: center",
            "justify-content: space-between",
            "transition: background-color 0.12s ease",
            "line-height: 1.4"
          ].join(";");

          if (isSelected) {
            item.style.fontWeight = "600";
            item.style.backgroundColor = isDark ? "rgba(255, 255, 255, 0.1)" : "rgba(0, 0, 0, 0.06)";
          }

          var label = document.createElement("span");
          label.textContent = opt.text || opt.label || opt.value || "";
          item.appendChild(label);

          if (isSelected) {
            var check = document.createElement("span");
            check.textContent = "✓";
            check.style.marginLeft = "10px";
            check.style.opacity = "0.8";
            check.style.fontSize = "12px";
            item.appendChild(check);
          }

          if (!opt.disabled) {
            item.addEventListener("mouseenter", function() {
              item.style.backgroundColor = isDark ? "rgba(255, 255, 255, 0.16)" : "rgba(0, 0, 0, 0.09)";
            });
            item.addEventListener("mouseleave", function() {
              item.style.backgroundColor = (opt.index === select.selectedIndex)
                ? (isDark ? "rgba(255, 255, 255, 0.1)" : "rgba(0, 0, 0, 0.06)")
                : "transparent";
            });
            item.addEventListener("mousedown", function(ev) {
              ev.preventDefault();
              ev.stopPropagation();
              var newIdx = opt.index;
              var changed = (select.selectedIndex !== newIdx || select.value !== opt.value);
              select.selectedIndex = newIdx;
              select.value = opt.value;
              closeMenu();
              if (changed) {
                try {
                  select.dispatchEvent(new Event("input", { bubbles: true, cancelable: true }));
                } catch(e) {}
                try {
                  select.dispatchEvent(new Event("change", { bubbles: true, cancelable: true }));
                } catch(e) {}
              }
            });
          }

          container.appendChild(item);
        })(el);
      }
    }

    renderOptions(menu, select.children);
    document.body.appendChild(menu);
  }

  document.addEventListener("mousedown", function(e) {
    if (activeMenu) {
      if (activeMenu.contains(e.target)) return;
      var tgtSelect = e.target.closest && e.target.closest("select");
      if (tgtSelect && tgtSelect === activeMenu.__target) {
        e.preventDefault();
        e.stopPropagation();
        closeMenu();
        return;
      }
      closeMenu();
    }
    var sel = e.target.closest && e.target.closest("select");
    if (sel && !sel.disabled) {
      e.preventDefault();
      e.stopPropagation();
      showSelectMenu(sel);
    }
  }, true);

  document.addEventListener("keydown", function(e) {
    if (e.key === "Escape") {
      closeMenu();
      return;
    }
    var sel = e.target.closest && e.target.closest("select");
    if (sel && !sel.disabled && (e.key === "Enter" || e.key === " " || e.key === "ArrowDown")) {
      e.preventDefault();
      e.stopPropagation();
      showSelectMenu(sel);
    }
  }, true);

  window.addEventListener("blur", closeMenu);
  window.addEventListener("resize", closeMenu);
  document.addEventListener("scroll", closeMenu, true);
})();
"""

    private const val MARKER = "__hk_select_polyfill_installed"

    /**
     * 将 Polyfill 注入到 HTML 正文中。
     *
     * 优先在 `</head>` 之前注入；若无 head，则在 `</body>` 之前注入；若都无，则追加在末尾。
     * 若已存在注入标记，则保持原样不重复注入。
     */
    fun injectIntoHtml(html: String): String {
        if (html.contains(MARKER)) return html

        val tag = "<script>/* HydroLink WebUI Select Polyfill */\n$SCRIPT\n</script>"
        val headIdx = html.indexOf("</head>", ignoreCase = true)
        if (headIdx >= 0) {
            return html.substring(0, headIdx) + tag + "\n" + html.substring(headIdx)
        }
        val bodyIdx = html.indexOf("</body>", ignoreCase = true)
        if (bodyIdx >= 0) {
            return html.substring(0, bodyIdx) + tag + "\n" + html.substring(bodyIdx)
        }
        return html + "\n" + tag
    }
}

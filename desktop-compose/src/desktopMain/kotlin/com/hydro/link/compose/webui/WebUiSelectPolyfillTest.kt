package com.hydro.link.compose.webui

/**
 * 验证 WebUI HTML <select> 交互增强（Polyfill）的脚本内容与注入逻辑。
 */
fun main() {
    println("[Test] 开始验证 WebUI Select Polyfill 逻辑...")

    // 1. 注入到含有 </head> 的标准页面
    val htmlWithHead = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>Test WebUI</title>
        </head>
        <body>
          <select><option value="gw">网关</option></select>
        </body>
        </html>
    """.trimIndent()
    val injectedHead = WebUiSelectPolyfill.injectIntoHtml(htmlWithHead)
    check(injectedHead.contains("__hk_select_polyfill_installed")) { "未注入标记" }
    check(injectedHead.contains("<script>/* HydroLink WebUI Select Polyfill */")) { "未注入脚本标签" }
    check(injectedHead.indexOf("<script>/* HydroLink WebUI Select Polyfill */") < injectedHead.indexOf("</head>")) {
        "脚本未注入在 </head> 之前"
    }
    println("  1. 含有 </head> 的页面注入验证通过")

    // 2. 注入到没有 <head> 但有 </body> 的页面
    val htmlWithBody = """
        <html>
        <body>
          <select><option value="ssh">SSH</option></select>
        </body>
        </html>
    """.trimIndent()
    val injectedBody = WebUiSelectPolyfill.injectIntoHtml(htmlWithBody)
    check(injectedBody.contains("__hk_select_polyfill_installed")) { "未注入标记" }
    check(injectedBody.indexOf("<script>/* HydroLink WebUI Select Polyfill */") < injectedBody.indexOf("</body>")) {
        "脚本未注入在 </body> 之前"
    }
    println("  2. 无 <head> 有 </body> 的页面注入验证通过")

    // 3. 注入到片段 HTML
    val fragment = "<div><select><option>A</option></select></div>"
    val injectedFragment = WebUiSelectPolyfill.injectIntoHtml(fragment)
    check(injectedFragment.startsWith(fragment)) { "片段前缀不正确" }
    check(injectedFragment.contains("__hk_select_polyfill_installed")) { "未追加注入脚本" }
    println("  3. 纯片段 HTML 追加注入验证通过")

    // 4. 幂等性防护：已注入页面不二次注入
    val twice = WebUiSelectPolyfill.injectIntoHtml(injectedHead)
    check(twice == injectedHead) { "幂等保护失效，内容发生了改变" }
    println("  4. 幂等性防护验证通过")

    // 5. 核心交互契约检查
    val script = WebUiSelectPolyfill.SCRIPT
    check(script.contains("document.addEventListener(\"mousedown\"")) { "缺少 mousedown 捕获阶段拦截" }
    check(script.contains("select.dispatchEvent(new Event(\"change\"")) { "缺少原生 change 事件派发" }
    check(script.contains("select.dispatchEvent(new Event(\"input\"")) { "缺少原生 input 事件派发" }
    check(script.contains("hk-select-overlay-menu")) { "缺少浮层类名" }
    check(script.contains("getEffectiveTheme")) { "缺少深浅色自适应函数" }
    println("  5. 核心交互契约（事件派发、主题自适应、浮层构建）验证通过")

    println("[Test] ✅ WebUI Select Polyfill 全部验证通过！")
}

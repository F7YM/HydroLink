package com.hydro.link.compose.webui

/**
 * 插件 WebUI 的跨域 Fetch / XHR 透明代理增强（Polyfill）。
 *
 * ## 为什么需要它
 *
 * 1. WebKitGTK 即使通过 [WebKitSecurity] 配置了 `disable-web-security = true`，
 *    在底层网络层发起跨域 HTTP 请求时，根据浏览器标准仍会强行附带 `Origin: http://127.0.0.1:<port>`
 *    （或 `http://127.0.1.<hash>:<port>`）。
 * 2. 像 Bilibili（`passport.bilibili.com`、`api.bilibili.com` 等）及其他主流服务端的 WAF / 防盗链机制，
 *    一旦检测到非官方域名的 `Origin` 请求头，会直接拦截并返回 `403 Forbidden` HTML 错误页。
 * 3. 浏览器的 Fetch 标准规定 `Origin`、`Referer`、`Cookie` 等属于「受限请求头（Forbidden Header Names）」，
 *    前端 JavaScript 无法在原生 `fetch()` 中随意修改或抹除 `Origin`。
 *
 * ## 解决方案
 *
 * 通过在 WebUI 页面加载前注入本增强脚本：
 * 1. 透明拦截对外部第三方域名的 `window.fetch` 与 `XMLHttpRequest` 请求；
 * 2. 将目标请求重定向至同源本地透明代理端点 `/api/hydro/proxy?url=<targetUrl>`；
 * 3. 将前端无法直接传递的受限请求头（如 `Cookie`、`Referer`、`User-Agent`）安全转换为 `X-Proxy-*` 头；
 * 4. 由 HydroLink 服务端负责发起真实的外部 HTTP 请求（不携带浏览器 `Origin`，支持防盗链头伪装），
 *    再将响应原样透传回前端；
 * 5. 全程对插件透明，插件源码完全无需感知，继续使用标准 `fetch('https://...')` 即可成功完成扫码与通信。
 */
object WebUiFetchPolyfill {

    const val SCRIPT: String = """
(function() {
  if (typeof window === "undefined" || window.__hk_fetch_polyfill_installed) return;
  window.__hk_fetch_polyfill_installed = true;

  function isExternalHttpUrl(url) {
    if (typeof url !== 'string') return false;
    if (!url.startsWith('http://') && !url.startsWith('https://')) return false;
    try {
      var parsed = new URL(url, window.location.href);
      var host = parsed.hostname;
      if (parsed.origin === window.location.origin) return false;
      var myPort = window.location.port || (window.location.protocol === 'https:' ? '443' : '80');
      var targetPort = parsed.port || (parsed.protocol === 'https:' ? '443' : '80');
      if (targetPort === myPort && (host === '127.0.0.1' || host === 'localhost' || host.startsWith('127.0.1.'))) return false;
      return true;
    } catch (e) {
      return false;
    }
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = async function(input, init) {
      var url = '';
      if (typeof input === 'string') {
        url = input;
      } else if (input && typeof input.url === 'string') {
        url = input.url;
      }

      if (isExternalHttpUrl(url)) {
        var proxyUrl = '/api/hydro/proxy?url=' + encodeURIComponent(url);
        var proxyInit = Object.assign({}, init);
        var customHeaders = {};

        if (input && input.headers) {
          try {
            if (input.headers instanceof Headers) {
              input.headers.forEach(function(v, k) { customHeaders[k] = v; });
            } else if (typeof input.headers === 'object') {
              Object.assign(customHeaders, input.headers);
            }
          } catch (e) {}
        }
        if (init && init.headers) {
          try {
            if (init.headers instanceof Headers) {
              init.headers.forEach(function(v, k) { customHeaders[k] = v; });
            } else if (Array.isArray(init.headers)) {
              init.headers.forEach(function(pair) {
                if (pair && pair.length >= 2) customHeaders[pair[0]] = pair[1];
              });
            } else if (typeof init.headers === 'object') {
              Object.assign(customHeaders, init.headers);
            }
          } catch (e) {}
        }

        var outHeaders = {};
        for (var k in customHeaders) {
          var lk = k.toLowerCase();
          if (lk === 'cookie') {
            outHeaders['X-Proxy-Cookie'] = customHeaders[k];
          } else if (lk === 'referer') {
            outHeaders['X-Proxy-Referer'] = customHeaders[k];
          } else if (lk === 'origin') {
            outHeaders['X-Proxy-Origin'] = customHeaders[k];
          } else if (lk === 'user-agent') {
            outHeaders['X-Proxy-User-Agent'] = customHeaders[k];
          } else if (lk === 'authorization') {
            outHeaders['X-Proxy-Authorization'] = customHeaders[k];
          } else {
            outHeaders[k] = customHeaders[k];
          }
        }
        proxyInit.headers = outHeaders;

        if (typeof input === 'string') {
          return originalFetch.call(this, proxyUrl, proxyInit);
        } else {
          if (input.method && !proxyInit.method) proxyInit.method = input.method;
          return originalFetch.call(this, proxyUrl, proxyInit);
        }
      }
      return originalFetch.apply(this, arguments);
    };
  }

  if (typeof XMLHttpRequest !== 'undefined') {
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function(method, url) {
      if (typeof url === 'string' && isExternalHttpUrl(url)) {
        this.__hk_is_proxy = true;
        url = '/api/hydro/proxy?url=' + encodeURIComponent(url);
      }
      return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
      if (this.__hk_is_proxy && typeof header === 'string') {
        var lh = header.toLowerCase();
        if (lh === 'cookie') header = 'X-Proxy-Cookie';
        else if (lh === 'referer') header = 'X-Proxy-Referer';
        else if (lh === 'origin') header = 'X-Proxy-Origin';
        else if (lh === 'user-agent') header = 'X-Proxy-User-Agent';
        else if (lh === 'authorization') header = 'X-Proxy-Authorization';
      }
      return originalSetRequestHeader.call(this, header, value);
    };
  }
})();
"""

    private const val MARKER = "__hk_fetch_polyfill_installed"

    /**
     * 将 Polyfill 注入到 HTML 正文中。
     *
     * 优先在 `</head>` 之前注入；若无 head，则在 `</body>` 之前注入；若都无，则追加在末尾。
     * 若已存在注入标记，则保持原样不重复注入。
     */
    fun injectIntoHtml(html: String): String {
        if (html.contains(MARKER)) return html

        val tag = "<script>/* HydroLink WebUI Fetch Polyfill */\n$SCRIPT\n</script>"
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

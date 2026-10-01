/*
 * HydroKit worker 宿主胶水（安卓端）。
 *
 * 与桌面端 desktop/internal/hydrokit/jsplugin.go 的 WorkerBundle 是同一套协议：
 *   poll(/api/hydro/worker/poll) → 调用插件代码 → respond(/api/hydro/worker/respond)
 * 差别只在于：桌面端把插件源码内联进生成的 blob worker；安卓端把这份胶水作为
 * 静态资源放在 assets 里，插件源码与 wasm 通过 /api/hydro/worker/asset 拉取。
 *
 * 配置来源（二选一，便于同一份文件也能在 Node 里跑 e2e）：
 *   1. 浏览器：worker.html?package=<id>&base=<origin>&entry=server.js&runtime=js
 *   2. Node  ：globalThis.__HK_CONFIG = { package, base, entry, runtime }
 */
(function () {
  "use strict";

  var cfg = (typeof globalThis !== "undefined" && globalThis.__HK_CONFIG) || null;
  if (!cfg && typeof location !== "undefined" && location.search) {
    var q = new URLSearchParams(location.search);
    cfg = {
      package: q.get("package") || "",
      base: q.get("base") || location.origin,
      entry: q.get("entry") || "server.js",
      runtime: q.get("runtime") || "js",
    };
  }
  cfg = cfg || {};

  var PKG = cfg.package || "";
  var P = (cfg.base || "").replace(/\/$/, "");
  var ENTRY = cfg.entry || "server.js";
  var RUNTIME = cfg.runtime === "wasm" ? "wasm" : "js";

  function report(msg) {
    try {
      if (typeof __hkLog === "function") __hkLog(msg); // Android 侧注入（可选）
      else if (typeof console !== "undefined") console.log("[hydrokit]", msg);
    } catch (e) {}
  }

  function req(path, opts) {
    // charset 必须写：安卓服务端是 NanoHTTPD，请求体没有 charset 时它按 US-ASCII
    // 解码，中文/emoji 会变成 U+FFFD（服务端也补了一道，见 normalizeRequestBodyCharset）
    return fetch(P + path, Object.assign({ headers: { "Content-Type": "application/json; charset=utf-8" } }, opts || {}))
      .then(function (r) { return r.json(); });
  }
  function remote(method, path, body) {
    return req(path, { method: method, body: body === undefined ? undefined : JSON.stringify(body) });
  }

  // ---- 设备侧存储：读来自本请求的 store 快照，写入随响应回传 ----
  var storeWatch = {};
  var storeWrites = {};

  var kit = {
    storage: {
      getItem: function (k, opts) {
        var scope = (opts && opts.scope) || "server";
        if (scope === "watch") {
          return storeWatch[k] === undefined ? Promise.resolve(null) : Promise.resolve(storeWatch[k]);
        }
        return remote("POST", "/api/hydro/worker/store", { package: PKG, op: "get", key: k, scope: scope })
          .then(function (d) { if (d && d.error) throw new Error(d.error); return (d && d.found) ? d.value : null; });
      },
      setItem: function (k, v, opts) {
        var scope = (opts && opts.scope) || "server";
        // 写穿到本地快照：同一个请求里"写完再读"要能读到（文档 §6 的示例就是这个
        // 语义）。不写穿的话，像 toggle 这种"读→取反→写→重读渲染"的插件会渲染出
        // 旧值，表现为"开关点一下关了，刷新又变回开"。
        if (scope === "watch") { storeWrites[k] = v; storeWatch[k] = v; return Promise.resolve({ ok: true }); }
        return remote("POST", "/api/hydro/worker/store", { package: PKG, op: "set", key: k, value: v, scope: scope });
      },
      removeItem: function (k, opts) {
        var scope = (opts && opts.scope) || "server";
        if (scope === "watch") { storeWrites[k] = null; delete storeWatch[k]; return Promise.resolve({ ok: true }); }
        return remote("POST", "/api/hydro/worker/store", { package: PKG, op: "remove", key: k, scope: scope });
      },
      clear: function (opts) {
        var scope = (opts && opts.scope) || "server";
        if (scope === "watch") { storeWrites = {}; storeWatch = {}; return Promise.resolve({ ok: true }); }
        return remote("POST", "/api/hydro/worker/store", { package: PKG, op: "clear", scope: scope });
      },
    },
    notify: function (n) {
      return remote("POST", "/api/hydro/worker/notify", {
        package: PKG, title: (n && n.title) || "", text: (n && n.text) || "",
      });
    },
    music: {
      action: function (a) { return remote("POST", "/api/hydro/worker/music", { package: PKG, action: a }); },
      // 读当前媒体（与 WASM 的 ctx.music 同构，artUrl/artBlur 是手表可达的地址）
      now: function () { return remote("POST", "/api/hydro/worker/music", { package: PKG, op: "now" }); },
    },
    // 通用 WebSocket 客户端：平台只给入口（地址校验 + 报错），连什么、说什么
    // 全归插件自己。返回原生 WebSocket 实例，用 onopen/onmessage/send/close。
    ws: {
      connect: function (url, handlers) {
        if (typeof url !== "string" || !/^wss?:\/\//i.test(url)) {
          throw new Error("kit.ws.connect 需要 ws:// 或 wss:// 地址");
        }
        var h = handlers || {};
        var sock = new WebSocket(url);
        if (typeof h.onopen === "function") { sock.onopen = function (e) { h.onopen(e, sock); }; }
        if (typeof h.onmessage === "function") { sock.onmessage = function (e) { h.onmessage(e, sock); }; }
        if (typeof h.onerror === "function") { sock.onerror = function (e) { h.onerror(e, sock); }; }
        if (typeof h.onclose === "function") { sock.onclose = function (e) { h.onclose(e, sock); }; }
        return sock;
      },
    },
    lock: function () { return remote("POST", "/api/hydro/worker/lock", { package: PKG }); },
    system: {
      perf: function () { return remote("POST", "/api/hydro/worker/system", { package: PKG, op: "perf" }); },
    },
    commands: {
      list: function () { return remote("POST", "/api/hydro/worker/commands", { package: PKG, op: "list" }); },
      run: function (i) { return remote("POST", "/api/hydro/worker/commands", { package: PKG, op: "run", index: i }); },
    },
    wol: {
      wake: function (opts) {
        var o = opts || {};
        return remote("POST", "/api/hydro/worker/wol/wake", {
          package: PKG,
          mac: o.mac || "",
          host: o.host || "255.255.255.255",
          port: typeof o.port === "number" ? o.port : 9,
        });
      },
      probe: function (opts) {
        var o = opts || {};
        return remote("POST", "/api/hydro/worker/wol/probe", {
          package: PKG,
          host: o.host || "",
          port: typeof o.port === "number" ? o.port : undefined,
          timeout: typeof o.timeout === "number" ? o.timeout : 1000,
        });
      },
    },
    udp: {
      send: function (opts) {
        var o = opts || {};
        return remote("POST", "/api/hydro/worker/udp/send", {
          package: PKG,
          host: o.host || "",
          port: o.port,
          data: o.data || "",
          encoding: o.encoding || "utf-8",
          broadcast: !!o.broadcast,
        });
      },
    },
    input: {
      screen: function () {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "screen" });
      },
      cursor: function () {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "cursor" });
      },
      move: function (x, y) {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "move", x: x, y: y });
      },
      moveBy: function (dx, dy) {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "move_by", dx: dx, dy: dy });
      },
      click: function (arg1, arg2, button, count) {
        if (typeof arg1 === "object" && arg1 !== null) {
          return remote("POST", "/api/hydro/worker/input", {
            package: PKG,
            op: "click",
            x: arg1.x,
            y: arg1.y,
            button: arg1.button || "left",
            count: typeof arg1.count === "number" ? arg1.count : 1,
          });
        }
        return remote("POST", "/api/hydro/worker/input", {
          package: PKG,
          op: "click",
          x: arg1,
          y: arg2,
          button: button || "left",
          count: typeof count === "number" ? count : 1,
        });
      },
      drag: function (arg1, y1, x2, y2, duration) {
        if (typeof arg1 === "object" && arg1 !== null) {
          return remote("POST", "/api/hydro/worker/input", {
            package: PKG,
            op: "drag",
            x1: arg1.x1,
            y1: arg1.y1,
            x2: arg1.x2,
            y2: arg1.y2,
            duration: arg1.duration,
          });
        }
        return remote("POST", "/api/hydro/worker/input", {
          package: PKG,
          op: "drag",
          x1: arg1,
          y1: y1,
          x2: x2,
          y2: y2,
          duration: duration,
        });
      },
      swipe: function (x1, y1, x2, y2, duration) {
        return this.drag(x1, y1, x2, y2, duration);
      },
      scroll: function (arg1, arg2) {
        if (typeof arg1 === "object" && arg1 !== null) {
          return remote("POST", "/api/hydro/worker/input", {
            package: PKG,
            op: "scroll",
            dx: arg1.dx || 0,
            dy: arg1.dy || 0,
          });
        }
        if (arg2 === undefined) {
          return remote("POST", "/api/hydro/worker/input", {
            package: PKG,
            op: "scroll",
            dx: 0,
            dy: arg1 || 0,
          });
        }
        return remote("POST", "/api/hydro/worker/input", {
          package: PKG,
          op: "scroll",
          dx: arg1 || 0,
          dy: arg2 || 0,
        });
      },
      key: function (k) {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "key", key: k });
      },
      shortcut: function (keys) {
        return remote("POST", "/api/hydro/worker/input", {
          package: PKG,
          op: "shortcut",
          keys: Array.isArray(keys) ? keys : [keys],
        });
      },
      text: function (str) {
        return remote("POST", "/api/hydro/worker/input", { package: PKG, op: "text", text: str });
      },
    },
    // 辅助 wasm（与「WASM 插件」不同，这是 JS 插件可选加载的算法模块）
    loadWasm: function (path) {
      return fetch(P + "/api/hydro/worker/asset?package=" + encodeURIComponent(PKG) + "&path=" + encodeURIComponent(path))
        .then(function (r) { if (!r.ok) throw new Error("asset " + path + ": " + r.status); return r.arrayBuffer(); })
        .then(function (buf) { return WebAssembly.instantiate(buf); })
        .then(function (m) { return { exports: m.instance.exports }; });
    },
  };

  // ---- 插件代码加载：JS（server.js 风格 CommonJS）或 WASM（ABI v1）----
  function loadJs(source) {
    var module = { exports: {} };
    try {
      var fn = new Function("module", "exports", "kit", source);
      fn(module, module.exports, kit);
      return module.exports;
    } catch (e) {
      report("server.js 加载失败: " + (e && e.message ? e.message : e));
      throw e;
    }
  }

  // WASM ABI v1（与桌面 WasmBundle 一致）：纯函数 + 线性内存
  //   hydro_abi_version() -> 1
  //   hydro_alloc(len) -> ptr ; hydro_free(ptr, len)
  //   hydro_view(ctxPtr, ctxLen) -> ptr（[u32 len][utf8 json]）
  //   import env.hydro_log(ptr, len)
  function loadWasm(bytes) {
    var mem = null;
    var env = {
      hydro_log: function (ptr, len) {
        try {
          var u8 = new Uint8Array(mem.buffer, ptr, len);
          report("wasm: " + new TextDecoder("utf-8").decode(u8));
        } catch (e) {}
      },
      abort: function () { throw new Error("wasm aborted"); },
    };
    return WebAssembly.instantiate(bytes, { env: env }).then(function (m) {
      var ex = m.instance.exports;
      mem = ex.memory;
      if (typeof ex.hydro_abi_version !== "function" || ex.hydro_abi_version() !== 1) {
        throw new Error("wasm ABI 版本不匹配（需要 hydro_abi_version()==1）");
      }
      return {
        view: function (surface, item, ctx) {
          var payload = JSON.stringify(ctx);
          var bytes = new TextEncoder().encode(payload);
          var ptr = ex.hydro_alloc(bytes.length);
          if (!ptr) throw new Error("hydro_alloc 失败");
          new Uint8Array(mem.buffer, ptr, bytes.length).set(bytes);
          var out = ex.hydro_view(ptr, bytes.length);
          ex.hydro_free(ptr, bytes.length);
          if (!out) return null;
          var len = new DataView(mem.buffer).getUint32(out, true);
          var json = new TextDecoder("utf-8").decode(new Uint8Array(mem.buffer, out + 4, len));
          return JSON.parse(json);
        },
        effects: function (view) {
          return (view && view.effects) || [];
        },
      };
    });
  }

  // WASM 插件的 view 需要宿主提供 ABI 上下文（权限门控）
  function fetchAbiContext(surface, item, reqObj) {
    return remote("POST", "/api/hydro/worker/context", {
      package: PKG, surface: surface, item: item, req: reqObj,
    });
  }

  function execEffects(view) {
    var effects = (view && view.effects) || [];
    if (!effects.length) return Promise.resolve();
    return effects.reduce(function (chain, eff) {
      return chain.then(function () {
        var op = eff && eff.op;
        if (op === "notify") return kit.notify(eff);
        if (op === "music") return kit.music.action(eff.action);
        if (op === "lock") return kit.lock();
        if (op === "storage") {
          var key = eff.key;
          var value = eff.value;
          if (value === null || value === undefined) return kit.storage.removeItem(key);
          return kit.storage.setItem(key, value);
        }
        if (op === "commands") return kit.commands.run(eff.index);
        if (op === "wol") return kit.wol.wake(eff);
        if (op === "udp") return kit.udp.send(eff);
        if (op === "input") {
          var action = eff.action || eff.type || eff.inputOp || "click";
          if (action === "screen") return kit.input.screen();
          if (action === "cursor") return kit.input.cursor();
          if (action === "move") return kit.input.move(eff.x, eff.y);
          if (action === "move_by" || action === "moveBy") return kit.input.moveBy(eff.dx, eff.dy);
          if (action === "click" || action === "tap") return kit.input.click(eff);
          if (action === "drag" || action === "swipe") return kit.input.drag(eff);
          if (action === "scroll") return kit.input.scroll(eff);
          if (action === "key") return kit.input.key(eff.key);
          if (action === "shortcut") return kit.input.shortcut(eff.keys);
          if (action === "text") return kit.input.text(eff.text);
          return remote("POST", "/api/hydro/worker/input", Object.assign({ package: PKG }, eff));
        }
        report("未知 effect: " + op);
        return null;
      });
    }, Promise.resolve());
  }

  // ---- 主循环 ----
  var loaded = null; // Promise<{ view(surface,item,req) }>
  function ensureLoaded() {
    if (loaded) return loaded;
    loaded = fetch(P + "/api/hydro/worker/asset?package=" + encodeURIComponent(PKG) + "&path=" + encodeURIComponent(ENTRY))
      .then(function (r) {
        if (!r.ok) throw new Error("拉取入口 " + ENTRY + " 失败: " + r.status);
        return RUNTIME === "wasm" ? r.arrayBuffer() : r.text();
      })
      .then(function (payload) {
        if (RUNTIME !== "wasm") {
          var exportsObj = loadJs(payload);
          return {
            view: function (surface, item, reqObj) {
              return Promise.resolve(exportsObj.view(surface, item, reqObj));
            },
          };
        }
        return loadWasm(payload); // 需要上下文时在 pump 里补
      });
    return loaded;
  }

  var announced = false;
  var stopped = false;

  // 供 Android 宿主调用：停止轮询（卸载/销毁 WebView 前）
  globalThis.__hkStop = function () { stopped = true; };

  (async function pump() {
    while (!stopped) {
      try {
        var r = await fetch(P + "/api/hydro/worker/poll?package=" + encodeURIComponent(PKG));
        var msg = await r.json();
        if (msg && msg.req) {
          var m = msg.req;
          storeWatch = (m.req && m.req.store) || {};
          storeWrites = {};
          var plugin = await ensureLoaded();
          var view;
          if (RUNTIME === "wasm") {
            var ctx = await fetchAbiContext(m.surface, m.item, m.req || {});
            view = plugin.view(m.surface, m.item, ctx);
            await execEffects(view);
          } else {
            view = await plugin.view(m.surface, m.item, m.req || {});
          }
          var hasWrites = false;
          for (var k in storeWrites) { hasWrites = true; break; }
          if (hasWrites) {
            if (view == null) view = {};
            if (typeof view.storage !== "object" || view.storage === null) view.storage = {};
            for (var kk in storeWrites) { view.storage[kk] = storeWrites[kk]; }
          }
          await remote("POST", "/api/hydro/worker/respond", {
            package: PKG, id: m.id, result: view == null ? null : view,
          });
        } else {
          await new Promise(function (res) { setTimeout(res, 300); });
        }
        if (!announced) {
          announced = true;
          report("worker ready: " + PKG + " (" + RUNTIME + ")");
        }
      } catch (e) {
        report("worker 循环出错: " + (e && e.message ? e.message : e));
        await new Promise(function (res) { setTimeout(res, 2000); });
      }
    }
  })();
})();

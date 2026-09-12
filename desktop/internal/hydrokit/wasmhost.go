package hydrokit

import (
	"bytes"
	"fmt"
)

// WasmBundle builds the WebWorker script that hosts a WASM plugin (ABI v1).
//
// The worker: loads plugin.wasm from the package assets, instantiates it with
// a HydroKit host environment, then serves the poll/respond loop exactly like
// the JS bundle — but instead of calling server.js it:
//
//  1. asks the Kit for the permission-gated ABI context
//     (POST /api/hydro/worker/context: request + time + system/music/storage)
//  2. calls the module's hydro_view(ctx) with the context JSON in linear memory
//  3. reads the length-prefixed result envelope and executes the returned
//     effects through the existing kit endpoints (notify/music/lock/storage/
//     commands), which keep enforcing manifest permissions
//  4. posts the view result back (root/refreshMs/storage)
//
// ABI v1 (JSON over linear memory, pure functions — no async host calls):
//
//	export hydro_abi_version() -> i32            // must be 1
//	export hydro_alloc(len) -> ptr
//	export hydro_free(ptr, len)
//	export hydro_view(ctxPtr, ctxLen) -> ptr     // [u32 len][utf8 json]
//	import env.hydro_log(ptr, len)               // optional debugging
func WasmBundle(pkg, entry, base string) []byte {
	var b bytes.Buffer
	b.WriteString("(function(){\n")
	fmt.Fprintf(&b, "var __P = %s;\n", jsonString(base))
	fmt.Fprintf(&b, "var __PKG = %s;\n", jsonString(pkg))
	fmt.Fprintf(&b, "var __WASM = %s;\n", jsonString(entry))
	b.WriteString(`var __exp = null, __mem = null, __announced = false;
function _req(path, opts) {
  return fetch(__P + path, Object.assign({ headers: { "Content-Type": "application/json" } }, opts || {})).then(function (r) {
    if (!r.ok) throw new Error(path + ": HTTP " + r.status);
    return r.json();
  });
}
function _post(path, body) {
  return _req(path, { method: "POST", body: JSON.stringify(body) });
}
function _u8(ptr, len) {
  return new Uint8Array(__mem.buffer, ptr, len);
}
function _str(ptr, len) {
  if (!len) return "";
  return new TextDecoder().decode(_u8(ptr, len));
}
function _writeJson(obj) {
  var bytes = new TextEncoder().encode(JSON.stringify(obj));
  var ptr = __exp.hydro_alloc(bytes.length);
  if (!ptr) throw new Error("hydro_alloc 失败");
  _u8(ptr, bytes.length).set(bytes);
  return { ptr: ptr, len: bytes.length };
}
function _readResult(ptr) {
  var len = new DataView(__mem.buffer).getUint32(ptr, true);
  return JSON.parse(_str(ptr + 4, len));
}
async function __load() {
  var res = await fetch(__P + "/api/hydro/worker/asset?package=" + encodeURIComponent(__PKG) +
    "&path=" + encodeURIComponent(__WASM));
  if (!res.ok) throw new Error("加载 " + __WASM + " 失败: HTTP " + res.status);
  var buf = await res.arrayBuffer();
  var mod = await WebAssembly.instantiate(buf, {
    env: {
      hydro_log: function (ptr, len) {
        try { console.log("[wasm " + __PKG + "] " + _str(ptr, len)); } catch (e) {}
      }
    }
  });
  __exp = mod.instance.exports;
  __mem = __exp.memory;
  var ver = __exp.hydro_abi_version ? __exp.hydro_abi_version() : 0;
  if (ver !== 1) throw new Error("ABI 版本不匹配: " + ver + "（宿主支持 1）");
}
async function __effects(list) {
  for (var i = 0; i < (list || []).length; i++) {
    var e = list[i] || {};
    try {
      if (e.op === "notify") {
        await _post("/api/hydro/worker/notify", { package: __PKG, title: e.title || "", text: e.text || "" });
      } else if (e.op === "music") {
        await _post("/api/hydro/worker/music", { package: __PKG, action: e.action || "" });
      } else if (e.op === "lock") {
        await _post("/api/hydro/worker/lock", { package: __PKG });
      } else if (e.op === "storage") {
        await _post("/api/hydro/worker/store", { package: __PKG, op: "set", key: e.key, value: e.value });
      } else if (e.op === "storage-remove") {
        await _post("/api/hydro/worker/store", { package: __PKG, op: "remove", key: e.key });
      } else if (e.op === "commands") {
        await _post("/api/hydro/worker/commands", { package: __PKG, op: e.action || "run", index: e.index || 0 });
      }
    } catch (err) {
      console.log("[wasm " + __PKG + "] effect 失败 " + JSON.stringify(e) + ": " + err);
    }
  }
}
async function __handle(r) {
  var ctx = await _post("/api/hydro/worker/context", {
    package: __PKG, surface: r.surface, item: r.item, req: r.req || {}
  });
  var inb = _writeJson(ctx);
  var outPtr;
  try {
    outPtr = __exp.hydro_view(inb.ptr, inb.len);
  } finally {
    if (__exp.hydro_free) __exp.hydro_free(inb.ptr, inb.len);
  }
  if (!outPtr) throw new Error("hydro_view 返回空指针");
  var out = _readResult(outPtr);
  await __effects(out && out.effects);
  return out;
}
(async function pump() {
  try {
    await __load();
  } catch (e) {
    postMessage({ type: "error", message: String(e && e.message || e) });
  }
  while (true) {
    try {
      var msg = await _req("/api/hydro/worker/poll?package=" + encodeURIComponent(__PKG));
      if (msg && msg.req) {
        var r = msg.req;
        try {
          var out = await __handle(r);
          await _post("/api/hydro/worker/respond", {
            package: __PKG, id: r.id, result: out == null ? null : out
          });
        } catch (e) {
          await _post("/api/hydro/worker/respond", {
            package: __PKG, id: r.id, error: String(e && e.message || e)
          });
        }
      } else {
        await new Promise(function (res) { setTimeout(res, 300); });
      }
      if (!__announced) { __announced = true; postMessage({ type: "ready" }); }
    } catch (e) {
      postMessage({ type: "error", message: String(e && e.message || e) });
      await new Promise(function (res) { setTimeout(res, 2000); });
    }
  }
})();
})();
`)
	return b.Bytes()
}

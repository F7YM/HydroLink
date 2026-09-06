package hydrokit

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// JSPlugin bridges a HydroPlugin implemented in JavaScript (server.js running
// in a WebWorker) to the in-process Plugin interface. It is registered by the
// runtime exactly like a Go plugin; calls are forwarded to the worker session
// over the WorkerBridge under the package id.
type JSPlugin struct {
	manifest *Manifest
	pkg      string
	caller   Caller
}

// NewJSPlugin returns a Plugin backed by the worker session of pkg.
// The manifest must come from the plugin's manifest.json (disk), not from
// server.js — registration is validated before any code runs.
func NewJSPlugin(manifest *Manifest, caller Caller) *JSPlugin {
	return &JSPlugin{manifest: manifest, pkg: manifest.Package.ID, caller: caller}
}

// Manifest returns the plugin's declared manifest.
func (p *JSPlugin) Manifest() Manifest { return *p.manifest }

// View forwards the request to the worker and returns the tree it produced.
func (p *JSPlugin) View(surface, item string, req Request) (*View, error) {
	payload, err := json.Marshal(map[string]any{
		"surface": surface,
		"item":    item,
		"req":     req,
	})
	if err != nil {
		return nil, fmt.Errorf("hydrokit: encode payload: %w", err)
	}
	data, err := p.caller.Call(p.pkg, "view", payload)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 || bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
		return nil, nil
	}
	var v View
	if err := json.Unmarshal(data, &v); err != nil {
		return nil, fmt.Errorf("hydrokit: decode worker view: %w", err)
	}
	return &v, nil
}

// WorkerBundle builds the self-contained worker script that hosts a plugin's
// server.js inside a WebWorker: CommonJS shim, kit global, and the poll/respond
// pump against base (the host HTTP server).
func WorkerBundle(pkg, serverJS, base string) []byte {
	// Build a JSON-safe bootstrap; plugin code is embedded via escaped string.
	var b bytes.Buffer
	b.WriteString("(function(){\n")
	fmt.Fprintf(&b, "var __P = %s;\n", jsonString(base))
	fmt.Fprintf(&b, "var __PKG = %s;\n", jsonString(pkg))
	b.WriteString(`var module = { exports: {} };
var exports = module.exports;
function _req(path, opts) {
  return fetch(__P + path, Object.assign({ headers: { "Content-Type": "application/json" } }, opts || {})).then(function(r){ return r.json(); });
}
function _remote(method, path, body) {
  return _req(path, { method: method, body: body === undefined ? undefined : JSON.stringify(body) });
}
// watch-scope storage is request-contextual: reads come from the store
// snapshot the container uploaded (msg.req.store), writes accumulate and
// are flushed in the response's storage field, which the container
// persists on the device.
var __storeWatch = {};
var __storeWrites = {};
var kit = {
  storage: {
    getItem: function (k, opts) {
      var scope = (opts && opts.scope) || "server";
      if (scope === "watch") {
        return __storeWatch[k] === undefined ? Promise.resolve(null) : Promise.resolve(__storeWatch[k]);
      }
      return _remote("POST", "/api/hydro/worker/store", { package: __PKG, op: "get", key: k, scope: scope }).then(function (d) {
        if (d && d.error) throw new Error(d.error);
        return (d && d.found) ? d.value : null;
      });
    },
    setItem: function (k, v, opts) {
      var scope = (opts && opts.scope) || "server";
      if (scope === "watch") { __storeWrites[k] = v; return Promise.resolve({ ok: true }); }
      return _remote("POST", "/api/hydro/worker/store", { package: __PKG, op: "set", key: k, value: v, scope: scope });
    },
    removeItem: function (k, opts) {
      var scope = (opts && opts.scope) || "server";
      if (scope === "watch") { __storeWrites[k] = null; return Promise.resolve({ ok: true }); }
      return _remote("POST", "/api/hydro/worker/store", { package: __PKG, op: "remove", key: k, scope: scope });
    },
    clear: function (opts) {
      var scope = (opts && opts.scope) || "server";
      if (scope === "watch") { __storeWrites = {}; return Promise.resolve({ ok: true }); }
      return _remote("POST", "/api/hydro/worker/store", { package: __PKG, op: "clear", scope: scope });
    }
  },
  notify: function (n) {
    return _remote("POST", "/api/hydro/worker/notify", { package: __PKG, title: (n && n.title) || "", text: (n && n.text) || "" });
  },
  music: {
    action: function (a) { return _remote("POST", "/api/hydro/worker/music", { package: __PKG, action: a }); }
  },
  lock: function () { return _remote("POST", "/api/hydro/worker/lock", { package: __PKG }); },
  commands: {
    list: function () { return _remote("POST", "/api/hydro/worker/commands", { package: __PKG, op: "list" }); },
    run: function (i) { return _remote("POST", "/api/hydro/worker/commands", { package: __PKG, op: "run", index: i }); }
  },
  loadWasm: function (path) {
    return fetch(__P + "/api/hydro/worker/asset?package=" + encodeURIComponent(__PKG) + "&path=" + encodeURIComponent(path))
      .then(function (r) { if (!r.ok) throw new Error("asset " + path + ": " + r.status); return r.arrayBuffer(); })
      .then(function (buf) { return WebAssembly.instantiate(buf); })
      .then(function (m) { return { exports: m.instance.exports }; });
  }
};
var __user = (function () {
  try {
    var fn = new Function("module", "exports", "kit", __PLUGIN);
    var m = { exports: {} };
    fn(m, m.exports, kit);
    return m.exports;
  } catch (e) {
    postMessage({ type: "error", message: "server.js 加载失败: " + String(e && e.message || e) });
    return null;
  }
})();
var __plugin = __user;
var __announced = false;
(async function pump() {
  while (true) {
    try {
      var r = await fetch(__P + "/api/hydro/worker/poll?package=" + encodeURIComponent(__PKG));
      var msg = await r.json();
      if (msg && msg.req) {
        var req = msg.req;
        __storeWatch = (req.req && req.req.store) || {};
        __storeWrites = {};
        try {
          var view = await __plugin.view(req.surface, req.item, req.req || {});
          var hasWrites = false;
          for (var k in __storeWrites) { hasWrites = true; break; }
          if (hasWrites) {
            if (view == null) view = {};
            if (typeof view.storage !== "object" || view.storage === null) view.storage = {};
            for (var kk in __storeWrites) { view.storage[kk] = __storeWrites[kk]; }
          }
          await _remote("POST", "/api/hydro/worker/respond", {
            package: __PKG, id: req.id, result: view == null ? null : view
          });
        } catch (e) {
          await _remote("POST", "/api/hydro/worker/respond", {
            package: __PKG, id: req.id, error: String(e && e.message || e)
          });
        }
      }
      if (!msg || !msg.req) { await new Promise(function (res) { setTimeout(res, 300); }); }
      if (!__announced) { __announced = true; postMessage({ type: "ready" }); }
    } catch (e) {
      postMessage({ type: "error", message: String(e && e.message || e) });
      await new Promise(function (res) { setTimeout(res, 2000); });
    }
  }
})();
})();
`)
	// Replace the plugin placeholder with the safe JSON-encoded source.
	out := b.String()
	return []byte(strings.Replace(out, "__PLUGIN", jsonString(serverJS), 1))
}

// jsonString returns a JSON-encoded string literal (also safe inside JS).
func jsonString(s string) string {
	b, err := json.Marshal(s)
	if err != nil {
		return `""`
	}
	return string(b)
}

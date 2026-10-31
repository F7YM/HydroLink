#!/usr/bin/env bash
#
# device-check.sh 的自检：起一个假服务端，验证检查脚本"该 PASS 的 PASS、该 FAIL 的 FAIL"。
#
# 为什么要给检查脚本写自检：它是拿来当真机验收依据的，如果它本身写错了（比如把
# 404 当成功、正则没匹配上），我们会拿到一份"全绿"的假报告——比没有检查更糟。
#
#   bash android/scripts/device-check-selftest.sh
#
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/device-check.sh"

MOCK_PY="$(mktemp --suffix=.py)"
cat >"$MOCK_PY" <<'PY'
import json, os, sys, re
from http.server import BaseHTTPRequestHandler, HTTPServer

MODE = os.environ.get("MOCK_MODE", "good")
STORE = {}

PLUGIN = {
    "package": {"id": "com.mock.plugin", "name": "Mock", "version": "1.0.0",
                "permissions": ["storage", "fetch"]},
    "apps": [{"id": "main", "name": "Mock"}],
    "widgets": [],
}

HOST_PAGE_GOOD = '<!DOCTYPE html><html><body><script src="/api/hydro/worker/host?file=worker.js"></script></body></html>'
HOST_PAGE_BAD = '<!DOCTYPE html><html><body><script src="worker.js"></script></body></html>'


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, ctype, body):
        raw = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, code, obj):
        self._send(code, "application/json", json.dumps(obj, ensure_ascii=False))

    def do_GET(self):
        path = self.path.split("?")[0]
        q = dict(re.findall(r"([^?&=]+)=([^&]*)", self.path))
        if path == "/api/ping":
            return self._json(200, {"status": "ok"})
        if path == "/api/version":
            return self._json(200, {"status": "ok", "app": "hydrolink", "version": "1.0.0"})
        if path == "/api/hydro/list":
            return self._json(200, {"plugins": [PLUGIN]})
        if path == "/api/hydro/worker/host":
            f = q.get("file", "worker.html")
            if f.startswith("../") or f not in ("worker.html", "worker.js"):
                return self._json(400, {"error": "不允许的宿主文件"})
            if f == "worker.html":
                return self._send(200, "text/html; charset=utf-8",
                                  HOST_PAGE_BAD if MODE == "bad" else HOST_PAGE_GOOD)
            if MODE == "bad":
                return self._send(404, "text/plain", "not found")
            return self._send(200, "application/javascript; charset=utf-8", "/* glue */")
        if path == "/api/music/art":
            return self._json(404, {"status": "error", "message": "没有可用的封面"})
        if path.startswith("/api/hydro/com.mock.plugin/"):
            if MODE == "bad":
                # 正文里塞一个 U+FFFD，检查脚本应当报"含乱码"
                return self._send(200, "application/json",
                                  '{"refreshMs":3000,"root":{"type":"scroll","props":{"text":"\ufffd\ufffd"}}}')
            return self._send(200, "application/json",
                              '{"refreshMs":3000,"root":{"type":"scroll","props":{"text":"正常"}}}')
        return self._json(404, {"status": "error", "message": "Not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace") if n else "{}"
        try:
            body = json.loads(raw)
        except Exception:
            body = {}
        path = self.path.split("?")[0]
        if path == "/api/hydro/worker/store":
            key = body.get("key")
            if body.get("op") == "set":
                STORE[key] = body.get("value")
                return self._json(200, {"status": "ok"})
            if body.get("op") == "get":
                if key in STORE:
                    return self._json(200, {"found": True, "value": STORE[key]})
                return self._json(200, {"found": False})
            return self._json(200, {"status": "ok"})
        if path == "/api/hydro/worker/system":
            if body.get("package") != "com.mock.plugin":
                return self._json(403, {"error": "plugin 不存在或清单不可读"})
            return self._json(200, {"cpu": 1.0})
        return self._json(404, {"status": "error"})


HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
FAIL=0

run_case() { # run_case <模式> <期望:pass|fail>
  local mode="$1" expect="$2" out
  MOCK_MODE="$mode" python3 "$MOCK_PY" "$PORT" &
  local pid=$!
  sleep 0.6
  out="$(BASE_URL="http://127.0.0.1:$PORT" bash "$CHECK" 2>&1)"
  local rc=$?
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

  if [ "$expect" = "pass" ]; then
    if [ "$rc" = "0" ] && ! grep -q FAIL <<<"$out"; then
      echo "  PASS 正常服务端 → 全绿（退出码 0）"
    else
      echo "  FAIL 正常服务端却报错："; echo "$out" | sed 's/^/      /'; FAIL=1
    fi
  else
    # bad 模式：胶水 404 + 正文乱码，必须全都被抓到且退出码非 0
    if [ "$rc" != "0" ] \
       && grep -q "胶水可取" <<<"$out" && grep -q FAIL <<<"$out" \
       && grep -q "无乱码" <<<"$out"; then
      echo "  PASS 坏服务端 → 抓到 $(grep -c FAIL <<<"$out") 项失败（退出码 $rc）"
      echo "$out" | grep FAIL | sed 's/^/      /'
    else
      echo "  FAIL 坏服务端没被抓全（rc=$rc）："; echo "$out" | sed 's/^/      /'; FAIL=1
    fi
  fi
}

echo "自检 device-check.sh："
run_case good pass
run_case bad fail

rm -f "$MOCK_PY"
if [ "$FAIL" = "0" ]; then echo "自检通过"; else echo "自检失败"; exit 1; fi

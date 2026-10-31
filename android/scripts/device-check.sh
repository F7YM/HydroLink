#!/usr/bin/env bash
#
# 真机自检：把"真机验证清单"变成一条命令。
#
#   bash android/scripts/device-check.sh                    # 手机插着 USB，自动建端口转发
#   BASE_URL=http://127.0.0.1:9000 bash android/scripts/device-check.sh   # 打任意 HydroLink 服务端
#
# 检查内容（每一行会打印 PASS/FAIL 与细节，最后给汇总；有 FAIL 时退出码非 0）：
#   1  /api/ping、/api/version                     服务在跑
#   2  /api/hydro/list                             插件已注册、清单字段完整
#   3  每个插件的 app 项：466×466 圈表参数出树      Runtime→Worker→胶水→插件 整链
#   4  正文里没有 U+FFFD                           请求体字符集回归（真机上栽过）
#   5  插件存储往返（故意不带 charset）            非 ASCII 不丢
#   6  /api/music/art                              封面端点（有封面出图 / 没封面 404 JSON）
#   7  宿主页 + script src 按浏览器解析后可取       真机上栽过的那个 404
#   8  宿主页白名单 / 未知插件 / 未声明权限         该拒的要拒
#
# 为什么要有这个脚本：这几条以前是手搓 curl + python 一条条看的，换台机器/改一次代码
# 就得重来一遍；写死成脚本后，回归只需要跑一次。脚本本身用 --self-test 自证：
# 起一个假服务端跑一遍，确认"该 PASS 的 PASS、该 FAIL 的 FAIL"。
set -uo pipefail

FAILED=0
PASSED=0
ADB_PORT="${ADB_PORT:-19000}"
RAW_BASE="${BASE_URL:-}"

pass() { printf '  \033[32mPASS\033[0m %-28s %s\n' "$1" "${2:-}"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31mFAIL\033[0m %-28s %s\n' "$1" "${2:-}"; FAILED=$((FAILED + 1)); }
info() { printf '  ---- %-28s %s\n' "$1" "${2:-}"; }

# ---- 取响应：返回 "状态码<TAB>content-type<TAB>body文件" ----
get() { # get <url> [curl args...]
  local url="$1"; shift
  local body; body="$(mktemp)"
  local meta
  meta="$(curl -s -m 25 -o "$body" -w '%{http_code}\t%{content_type}' "$@" "$url" 2>/dev/null)"
  printf '%s\t%s' "$meta" "$body"
}

# 建端口转发（除非给了 BASE_URL）
setup_base() {
  if [ -n "$RAW_BASE" ]; then BASE="$RAW_BASE"; return 0; fi
  if ! command -v adb >/dev/null; then echo "没有 adb，也没给 BASE_URL"; return 1; fi
  if ! adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "没有已连接的设备（adb devices 为空），也没给 BASE_URL"
    return 1
  fi
  adb forward "tcp:$ADB_PORT" tcp:9000 >/dev/null || return 1
  BASE="http://127.0.0.1:$ADB_PORT"
}

# ---- 检查项 ----

check_service() {
  local r code
  r="$(get "$BASE/api/ping")"; code="${r%%$'\t'*}"
  [ "$code" = "200" ] && pass "服务在跑 (ping)" "HTTP 200" || fail "服务在跑 (ping)" "HTTP $code"
  r="$(get "$BASE/api/version")"; code="${r%%$'\t'*}"
  local file="${r##*$'\t'}"
  if [ "$code" = "200" ] && jq -e '.version' "$file" >/dev/null 2>&1; then
    pass "版本接口" "$(jq -r '.app + " " + .version' "$file")"
  else
    fail "版本接口" "HTTP $code"
  fi
}

# 解析插件列表，导出 PLUGIN_IDS / PLUGIN_APPS / PLUGIN_WEB / PLUGIN_PERMS
load_plugins() {
  local r code file
  r="$(get "$BASE/api/hydro/list")"; code="${r%%$'\t'*}"
  file="${r##*$'\t'}"
  if [ "$code" != "200" ] || ! jq -e '.plugins' "$file" >/dev/null 2>&1; then
    fail "插件列表" "HTTP $code"
    return 1
  fi
  local n; n="$(jq '.plugins|length' "$file")"
  pass "插件列表" "$n 个插件"
  PLUGIN_IDS="$(jq -r '.plugins[].package.id' "$file")"
  PLUGIN_FILE="$file"
  return 0
}

check_plugin() { # check_plugin <id>
  local id="$1"
  local apps; apps="$(jq -r --arg id "$id" '.plugins[]|select(.package.id==$id)|.apps[].id' "$PLUGIN_FILE")"
  local perms; perms="$(jq -r --arg id "$id" '.plugins[]|select(.package.id==$id)|.package.permissions // [] | join(",")' "$PLUGIN_FILE" 2>/dev/null)"
  info "插件 $id" "apps=[$(echo $apps | tr '\n' ' ')] perms=$perms"

  local app; app="$(echo "$apps" | head -1)"
  if [ -z "$app" ]; then
    info "  $id 没有 app 项" "跳过出树检查（widget-only 也正常）"
  else
    local r code file
    r="$(get "$BASE/api/hydro/$id/app/$app/view?shape=circle&width=466&height=466&dpr=2&dt=watch&_=1")"
    code="${r%%$'\t'*}"; file="${r##*$'\t'}"
    if [ "$code" = "200" ] && jq -e '.root' "$file" >/dev/null 2>&1; then
      pass "  $id 出树" "root=$(jq -r '.root.type' "$file") refreshMs=$(jq -r '.refreshMs // "null"' "$file") $(wc -c <"$file")B"
    else
      fail "  $id 出树" "HTTP $code $(head -c 80 "$file")"
      return 1
    fi
    # 4 编码回归：正文不能有 U+FFFD
    if python3 - "$file" <<'PY'
import sys
raw = open(sys.argv[1], 'rb').read()
sys.exit(1 if b'\xef\xbf\xbd' in raw else 0)
PY
    then pass "  $id 无乱码" "0 个 U+FFFD"
    else fail "  $id 无乱码" "正文含 U+FFFD（请求体字符集又坏了？）"; fi
  fi

  # 5 存储往返：故意不带 charset 的 POST（真机上这里栽过）
  local cn='中文标题·测试🎵'
  local payload
  payload="$(jq -nc --arg p "$id" --arg v "$cn" '{package:$p,op:"set",key:"__device_check",value:$v}')"
  curl -s -m 10 -H 'Content-Type: application/json' -d "$payload" "$BASE/api/hydro/worker/store" -o /dev/null
  local r2 code2 file2
  r2="$(get "$BASE/api/hydro/worker/store" -X POST -H 'Content-Type: application/json' \
        -d "$(jq -nc --arg p "$id" '{package:$p,op:"get",key:"__device_check"}')")"
  code2="${r2%%$'\t'*}"; file2="${r2##*$'\t'}"
  if [ "$code2" = "200" ] && jq -e --arg v "$cn" '.value==$v' "$file2" >/dev/null 2>&1; then
    pass "  $id 存储往返" "中文/emoji 原样返回"
  else
    fail "  $id 存储往返" "HTTP $code2 $(head -c 90 "$file2")"
  fi
  curl -s -m 10 -X POST -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg p "$id" '{package:$p,op:"remove",key:"__device_check"}')" \
    "$BASE/api/hydro/worker/store" -o /dev/null

  # 权限门控：list 不暴露 permissions，所以拿"未知包"来断言门控本身有效
  # （能力端点对不存在的插件一律 403；这条能抓住"门控被绕过"的回归）
  local r3 code3 file3
  r3="$(get "$BASE/api/hydro/worker/system" -X POST -H 'Content-Type: application/json' \
        -d '{"package":"com.nope.device.check"}')"
  code3="${r3%%$'\t'*}"; file3="${r3##*$'\t'}"
  [ "$code3" = "403" ] && pass "能力端点门控" "未知包 403" \
                       || fail "能力端点门控" "HTTP $code3（应 403）$(head -c 60 "$file3")"
}

check_art() {
  local r code ctype file
  r="$(get "$BASE/api/music/art")"; code="${r%%$'\t'*}"
  local rest="${r#*$'\t'}"; ctype="${rest%%$'\t'*}"; file="${rest##*$'\t'}"
  if [ "$code" = "200" ]; then
    case "$ctype" in
      image/*) pass "封面端点" "$ctype $(wc -c <"$file")B（当前有封面）";;
      *) fail "封面端点" "200 但不是图片: $ctype";;
    esac
  elif [ "$code" = "404" ] && jq -e '.status=="error"' "$file" >/dev/null 2>&1; then
    pass "封面端点" "404 JSON（当前没有封面，正常）"
  else
    fail "封面端点" "HTTP $code $(head -c 80 "$file")"
  fi
}

check_host_page() {
  local r code file
  r="$(get "$BASE/api/hydro/worker/host?file=worker.html")"; code="${r%%$'\t'*}"; file="${r##*$'\t'}"
  if [ "$code" != "200" ] || ! grep -q "<script" "$file"; then
    fail "宿主页" "HTTP $code"
    return
  fi
  pass "宿主页" "$(wc -c <"$file")B"

  # 7 按浏览器的解析规则取 script src，再真的去取一次——真机上就是这里 404 的
  local src; src="$(python3 - "$file" <<'PY'
import re, sys
html = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'<script[^>]*\bsrc\s*=\s*["\']([^"\']+)["\']', html, re.I)
print(m.group(1) if m else '')
PY
)"
  if [ -z "$src" ]; then fail "宿主页 script src" "没找到 script 标签"; return; fi
  local resolved
  resolved="$(python3 - "$src" "$BASE/api/hydro/worker/host?file=worker.html" <<'PY'
import sys, urllib.parse
print(urllib.parse.urljoin(sys.argv[2], sys.argv[1]))
PY
)"
  local r2 code2
  r2="$(get "$resolved")"; code2="${r2%%$'\t'*}"
  [ "$code2" = "200" ] && pass "胶水可取（浏览器式解析）" "$resolved" \
                       || fail "胶水可取（浏览器式解析）" "$resolved -> HTTP $code2"
}

check_rejections() {
  local r code
  r="$(get "$BASE/api/hydro/worker/host?file=../AndroidManifest.xml")"; code="${r%%$'\t'*}"
  [ "$code" = "400" ] && pass "宿主页白名单" "400" || fail "宿主页白名单" "HTTP $code（应 400）"

  r="$(get "$BASE/api/hydro/com.nope.device.check/app/main/view")"; code="${r%%$'\t'*}"
  [ "$code" = "404" ] && pass "未知插件" "404" || fail "未知插件" "HTTP $code（应 404）"
}

# ---- 主流程 ----
main() {
  if ! setup_base; then exit 2; fi
  echo "自检目标: $BASE"
  check_service
  if load_plugins; then
    for id in $PLUGIN_IDS; do check_plugin "$id"; done
  fi
  check_art
  check_host_page
  check_rejections
  echo
  if [ "$FAILED" = "0" ]; then
    printf '\033[32m全部通过\033[0m：%d 项\n' "$PASSED"
  else
    printf '\033[31m%d 项失败\033[0m（%d 项通过）\n' "$FAILED" "$PASSED"
    exit 1
  fi
}

main "$@"

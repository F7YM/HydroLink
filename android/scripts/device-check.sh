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
#   9  /api/hydro/worker/media → 分段 GIF           真视频切出来是 GIF89a、
#                                                   每段 ≥2 帧且画面在动、相邻段画面不同
#      （第 9 条是"假帧"回归的现场判据：静态封面 / 渐变合成帧 / 单帧图 全在这里露馅。
#        adb 模式下脚本自己用 ffmpeg 造样本视频 + adb reverse 递给手机；也可以
#        直接给 MEDIA_URL=<手机能取到的视频地址> 跳过造样本。）
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

  # 切片是"重量级"能力：未声明的包必须被门控挡在外面
  r="$(get "$BASE/api/hydro/worker/media" -X POST -H 'Content-Type: application/json' \
        -d '{"package":"com.nope.device.check","op":"prepare","url":"http://x/y.mp4"}')"
  code="${r%%$'\t'*}"
  [ "$code" = "403" ] && pass "切片能力门控" "未知包 403" || fail "切片能力门控" "HTTP $code（应 403）"
}

# ---- 视频切片（真视频 → 真解码 → GIF89a 分段）----
check_media() {
  # 切片按清单权限门控：先找一个声明了 media 的插件来当调用方
  local pkg
  pkg="$(jq -r '.plugins[]? | select(((.package.permissions // []) | index("media"))) | .package.id' "$PLUGIN_FILE" 2>/dev/null | head -1)"
  if [ -z "$pkg" ]; then
    info "视频切片" "没有插件声明 media 权限，跳过"
    return 0
  fi

  local tmpdir; tmpdir="$(mktemp -d)"
  local http_pid="" media_port="" media_url="${MEDIA_URL:-}"
  cleanup_media() {
    [ -n "$http_pid" ] && kill "$http_pid" 2>/dev/null
    if [ -n "$media_port" ] && [ -z "$RAW_BASE" ]; then adb reverse --remove "tcp:$media_port" >/dev/null 2>&1; fi
    rm -rf "$tmpdir"
  }

  if [ -z "$media_url" ]; then
    # 样本视频必须**手机能取到**：adb 模式下反向映射一个开发机端口给它
    if [ -n "$RAW_BASE" ]; then
      info "视频切片" "BASE_URL 模式没给 MEDIA_URL（样本视频手机取不到），跳过"
      rm -rf "$tmpdir"; return 0
    fi
    if ! command -v ffmpeg >/dev/null 2>&1; then
      info "视频切片" "没有 ffmpeg，造不出样本视频，跳过"
      rm -rf "$tmpdir"; return 0
    fi
    if ! ffmpeg -hide_banner -loglevel error \
        -f lavfi -i "testsrc=size=320x180:rate=10:duration=3" \
        -pix_fmt yuv420p -c:v libx264 -preset ultrafast -movflags +faststart \
        "$tmpdir/sample.mp4" >/dev/null 2>&1; then
      info "视频切片" "ffmpeg 造样本视频失败，跳过"
      rm -rf "$tmpdir"; return 0
    fi
    media_port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
    python3 -m http.server "$media_port" --bind 127.0.0.1 --directory "$tmpdir" >/dev/null 2>&1 &
    http_pid=$!
    if ! adb reverse "tcp:$media_port" "tcp:$media_port" >/dev/null 2>&1; then
      info "视频切片" "adb reverse 失败（手机取不到样本视频），跳过"
      cleanup_media; return 0
    fi
    media_url="http://127.0.0.1:$media_port/sample.mp4"
    sleep 0.3
  fi
  info "视频切片" "调用方 $pkg，样本 $media_url"

  # 拉起切片作业
  local payload="$tmpdir/prepare.json" r code file
  jq -nc --arg p "$pkg" --arg u "$media_url" \
    '{package:$p,op:"prepare",url:$u,startMs:0,segmentMs:1000,fps:5,width:240,height:135,maxSegments:3}' \
    > "$payload"
  r="$(get "$BASE/api/hydro/worker/media" -X POST -H 'Content-Type: application/json' --data-binary "@$payload")"
  code="${r%%$'\t'*}"; file="${r##*$'\t'}"
  if [ "$code" != "200" ]; then
    fail "视频切片 prepare" "HTTP $code $(head -c 140 "$file")"
    cleanup_media; return 1
  fi
  local seg_count first_url
  seg_count="$(jq -r '.segments | length' "$file" 2>/dev/null)"
  first_url="$(jq -r '.segments[0].url // ""' "$file" 2>/dev/null)"
  if [ -z "$seg_count" ] || [ "$seg_count" -lt 2 ] || [ -z "$first_url" ]; then
    fail "视频切片 prepare" "分段不足（$seg_count 段）：$(head -c 140 "$file")"
    cleanup_media; return 1
  fi
  case "$first_url" in
    http*://*) : ;;
    *) fail "视频切片 prepare" "分段必须是绝对 URL（手表按 URL 直取，相对地址取不到）: $first_url"
       cleanup_media; return 1;;
  esac
  pass "视频切片 prepare" "$(jq -r '"\(.width)x\(.height) fps=\(.fps) 共 \(.segmentCount) 段"' "$file")"

  # 取前两段（分段 URL 的 host 是手机的局域网地址，脚本改写成转发地址再取）
  local p0 p1 f0 f1 c0 c1
  p0="$(jq -r '.segments[0].url' "$file" | sed -E 's#^https?://[^/]+##')"
  p1="$(jq -r '.segments[1].url' "$file" | sed -E 's#^https?://[^/]+##')"
  r="$(get "$BASE$p0")"; c0="${r%%$'\t'*}"; f0="${r##*$'\t'}"
  r="$(get "$BASE$p1")"; c1="${r%%$'\t'*}"; f1="${r##*$'\t'}"
  if [ "$c0" != "200" ] || [ "$c1" != "200" ]; then
    fail "分段可下载" "HTTP $c0 / $c1 $(head -c 80 "$f0")"
    cleanup_media; return 1
  fi
  pass "分段可下载" "第 0 段 $(wc -c <"$f0")B / 第 1 段 $(wc -c <"$f1")B"

  if [ "$(head -c 6 "$f0")" = "GIF89a" ] && [ "$(head -c 6 "$f1")" = "GIF89a" ]; then
    pass "切片段是 GIF89a" "两段魔数都对"
  else
    fail "切片段是 GIF89a" "头 6 字节不是 GIF89a（$(head -c 6 "$f0" | od -c | head -1)）"
  fi

  # 解码两段，量出：帧数、段内画面变化、相邻段画面差异
  local verdict
  verdict="$(python3 - "$f0" "$f1" <<'PY'
import sys

def u16(b, p):
    return b[p] | (b[p + 1] << 8)

def sub_blocks(b, p):
    out = bytearray()
    while p < len(b):
        n = b[p]; p += 1
        if n == 0:
            break
        out += b[p:p + n]; p += n
    return bytes(out), p

def lzw(min_code, data, count):
    clear = 1 << min_code
    end = clear + 1
    prefix = [-1] * 4096
    suffix = [0] * 4096
    first = [0] * 4096
    for i in range(clear):
        suffix[i] = i
        first[i] = i
    out = []
    size = min_code + 1
    nxt = end + 1
    prev = -1
    pos = 0
    total = len(data) * 8
    while pos + size <= total and len(out) < count:
        code = 0
        for i in range(size):
            bit = (data[(pos + i) >> 3] >> ((pos + i) & 7)) & 1
            code |= bit << i
        pos += size
        if code == clear:
            size = min_code + 1
            nxt = end + 1
            prev = -1
            continue
        if code == end:
            break
        if prev < 0:
            out.append(suffix[code])
            prev = code
            continue
        stack = []
        target = code if code < nxt else prev
        if code >= nxt:
            stack.append(first[prev])
        walk = target
        while prefix[walk] >= 0:
            stack.append(suffix[walk])
            walk = prefix[walk]
        stack.append(suffix[walk])
        while stack and len(out) < count:
            out.append(stack.pop())
        if nxt < 4096:
            prefix[nxt] = prev
            suffix[nxt] = first[target]
            first[nxt] = first[prev]
            nxt += 1
            # 解码器的码长增长判据与编码器**差一拍**，见 GifProbe.kt 的注释
            if nxt == (1 << size) and size < 12:
                size += 1
        prev = code
    return out

def read_gif(path):
    b = open(path, 'rb').read()
    if b[:6] not in (b'GIF89a', b'GIF87a'):
        raise ValueError('not a gif')
    p = 6
    packed = b[p + 4]
    p += 7
    gct = b''
    if packed & 0x80:
        n = 1 << ((packed & 7) + 1)
        gct = b[p:p + n * 3]; p += n * 3
    frames = []
    while p < len(b):
        blk = b[p]; p += 1
        if blk == 0x3B:
            break
        if blk == 0x21:
            p += 1
            _, p = sub_blocks(b, p)
        elif blk == 0x2C:
            fw = u16(b, p + 4); fh = u16(b, p + 6)
            flags = b[p + 8]; p += 9
            pal = gct
            if flags & 0x80:
                n = 1 << ((flags & 7) + 1)
                pal = b[p:p + n * 3]; p += n * 3
            mcs = b[p]; p += 1
            data, p = sub_blocks(b, p)
            frames.append((lzw(mcs, data, fw * fh), pal, fw, fh))
        else:
            raise ValueError('unknown block 0x%02x' % blk)
    return frames

def rgb(frame, i):
    idx = frame[0][i] if i < len(frame[0]) else 0
    pal = frame[1]
    off = idx * 3
    if off + 3 <= len(pal):
        return pal[off], pal[off + 1], pal[off + 2]
    return 0, 0, 0

def mean_diff(a, b):
    if a is None or b is None:
        return 0.0
    if a[2] != b[2] or a[3] != b[3]:
        return 255.0  # 尺寸都不一样，当然算"不同"
    n = a[2] * a[3]
    total = 0
    for i in range(n):
        x = rgb(a, i); y = rgb(b, i)
        total += abs(x[0] - y[0]) + abs(x[1] - y[1]) + abs(x[2] - y[2])
    return total / (3.0 * n)

try:
    fa = read_gif(sys.argv[1])
    fb = read_gif(sys.argv[2])
    print('frames0=%d' % len(fa))
    print('frames1=%d' % len(fb))
    print('intra=%.2f' % (mean_diff(fa[0], fa[1]) if len(fa) > 1 else 0.0))
    print('inter=%.2f' % mean_diff(fa[0], fb[0]))
except Exception as exc:
    print('error=%s' % exc)
    sys.exit(1)
PY
)"
  if grep -q '^error=' <<<"$verdict"; then
    fail "切片能解码" "解不开：$(sed -n 's/^error=//p' <<<"$verdict")"
    cleanup_media; return 1
  fi
  local frames0 frames1 intra inter
  frames0="$(sed -n 's/^frames0=//p' <<<"$verdict")"
  frames1="$(sed -n 's/^frames1=//p' <<<"$verdict")"
  intra="$(sed -n 's/^intra=//p' <<<"$verdict")"
  inter="$(sed -n 's/^inter=//p' <<<"$verdict")"

  if [ "${frames0:-0}" -ge 2 ] && [ "${frames1:-0}" -ge 2 ] && awk -v v="${intra:-0}" 'BEGIN{exit !(v>=1.0)}'; then
    pass "切片是真动图" "帧数 $frames0/$frames1，段内画面差异 $intra"
  else
    fail "切片是真动图" "帧数 $frames0/$frames1，段内差异 $intra（帧数 <2 或段内画面不动 = 拿静帧冒充）"
  fi
  if awk -v v="${inter:-0}" 'BEGIN{exit !(v>=1.0)}'; then
    pass "相邻段画面不同" "差异 $inter"
  else
    fail "相邻段画面不同" "差异 $inter（相邻段一模一样 = 假切片）"
  fi

  # 顺手验证 release（作业内存回收）后不再可取段
  curl -s -m 5 -X POST -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg p "$pkg" --arg j "$(jq -r '.job' "$file")" '{package:$p,op:"release",job:$j}')" \
    "$BASE/api/hydro/worker/media" >/dev/null 2>&1
  r="$(get "$BASE$p0")"; code="${r%%$'\t'*}"
  [ "$code" = "410" ] && pass "release 后回收" "410" || fail "release 后回收" "HTTP $code（应 410）"

  cleanup_media
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
  check_media
  echo
  if [ "$FAILED" = "0" ]; then
    printf '\033[32m全部通过\033[0m：%d 项\n' "$PASSED"
  else
    printf '\033[31m%d 项失败\033[0m（%d 项通过）\n' "$FAILED" "$PASSED"
    exit 1
  fi
}

main "$@"

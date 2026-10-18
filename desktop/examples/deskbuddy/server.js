// 桌搭（Desk Buddy）— HydroPlugin（JS 运行时）
//
// 正在播放的圆屏界面：模糊封面铺底、顶部时钟、左侧封面、右侧歌名/歌手/歌词。
//
// 「SPlayer 歌词集成」完全是本插件自己的事：用平台给的通用 WebSocket 入口
// `kit.ws.connect()` 连 SPlayer 的本地服务（默认 ws://127.0.0.1:25885），
// 发什么消息、怎么解析歌词、当前唱到哪一句都在这里。HydroKit 只是提供网络能力，
// 不认识任何第三方播放器。
//
// 版式两套，按开关切换：
//   · 开了歌词集成 → 设计稿版式（内容盒坐标）+ 0.5s 刷新
//   · 没开         → 原来的居中版式 + 3s 刷新
//
// 存储键（都是插件自己的 server 作用域）：
//   fontScale / blur / dim        外观，WebUI 写
//   splayerLyrics / splayerPort   歌词集成开关与端口，WebUI 写
//   splayerState                  本插件写给 WebUI 的连接状态

// ---- 设计稿（466×466 圆屏）常量 ----
var DESIGN = 466;
var D_COVER = 135, D_COVER_LEFT = 46, D_RIGHT_PAD = 42, D_GAP = 18, D_CLOCK_TOP = 30, D_RADIUS = 21;
// 容器圆屏安全区内边距（vela 的 .safe-circle）：绝对定位的原点是**内容盒**且超出会被硬裁，
// 所以设计稿的屏幕坐标要先减掉它。
var SAFE_INSET_X = 42, SAFE_INSET_TOP = 30;
var LYRICS_REFRESH_MS = 500, CLASSIC_REFRESH_MS = 3000;
var DEFAULT_PORT = 25885;

// ---- SPlayer 客户端状态（worker 常驻内存）----
var sp = {
  sock: null, state: 'closed', error: '', port: DEFAULT_PORT, lastTry: 0,
  lines: [], posMs: 0, posAt: 0, playing: false, title: '', artist: '', hasTrack: false
};
var spLastState = '';

function spConnected() { return sp.state === 'open'; }

function spConnect(port) {
  if (sp.state === 'connecting' || sp.state === 'open') return;
  var now = Date.now();
  if (now - sp.lastTry < 3000) return; // 失败后退避，别每 0.5s 重连一次
  sp.lastTry = now;
  sp.port = port;
  sp.error = '';
  sp.state = 'connecting';
  var sock;
  try {
    sock = kit.ws.connect('ws://127.0.0.1:' + port, {
      onopen: function (e, s) {
        sp.state = 'open';
        sp.error = '';
        try { s.send('{"type":"get-song-info"}'); } catch (err) {}
        publishStatus();
      },
      onmessage: function (e) { spOnMessage(e && e.data); },
      onerror: function () { sp.error = 'WebSocket 出错'; publishStatus(); },
      onclose: function () {
        sp.state = 'closed';
        sp.sock = null;
        sp.lines = [];
        publishStatus();
      }
    });
  } catch (err) {
    sp.state = 'closed';
    sp.error = String((err && err.message) || err);
    publishStatus();
    return;
  }
  sp.sock = sock;
}

function spClose() {
  if (sp.sock) { try { sp.sock.close(); } catch (e) {} }
  sp.sock = null;
  sp.state = 'closed';
  sp.lines = [];
  sp.hasTrack = false;
  publishStatus();
}

function spSend(obj) {
  if (!sp.sock || !spConnected()) return;
  try { sp.sock.send(JSON.stringify(obj)); } catch (e) {}
}

// 心跳：SPlayer 收到 PING 会回 PONG
setInterval(function () { if (spConnected()) { try { sp.sock.send('PING'); } catch (e) {} } }, 20000);

// ---- SPlayer 协议 ----

function spOnMessage(raw) {
  if (typeof raw !== 'string') return;
  var t = raw.trim();
  if (t === 'PONG') return;
  var msg;
  try { msg = JSON.parse(t); } catch (e) { return; }
  if (!msg) return;
  var d = msg.data || {};

  if (msg.type === 'song-info') {
    sp.title = d.name || d.title || sp.title;
    sp.artist = firstStr(d, ['artist', 'artists', 'artistName']) || sp.artist;
    sp.hasTrack = !!sp.title;
    var lines = parseLines(d.lrcData);
    if (lines) sp.lines = lines;
    sp.posMs = timeToMs(numOr(d.currentTime, -1), numOr(d.duration, 0));
    sp.posAt = Date.now();
    sp.playing = d.playStatus === 'play' || d.playStatus === 'playing' || d.playStatus === true;
  } else if (msg.type === 'lyric-change') {
    var l = parseLines(d.lrcData) || parseLines(d.yrcData);
    if (l) sp.lines = l;
  } else if (msg.type === 'song-change') {
    // 切歌：旧歌词立刻作废，等 lyric-change / 下次 get-song-info
    sp.title = d.name || d.title || '';
    sp.artist = firstStr(d, ['artist', 'artists']) || '';
    sp.hasTrack = !!sp.title;
    sp.lines = [];
    sp.posMs = 0;
    sp.posAt = Date.now();
  } else if (msg.type === 'progress-change') {
    sp.posMs = timeToMs(numOr(d.currentTime, 0), numOr(d.duration, 0));
    sp.posAt = Date.now();
  } else if (msg.type === 'status-change') {
    sp.playing = !!d.status;
    sp.posAt = Date.now();
  } else if (msg.type === 'error') {
    sp.error = d.message || 'SPlayer 报错';
  }
  publishStatus();
}

function numOr(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }

function firstStr(o, keys) {
  for (var i = 0; i < keys.length; i++) {
    var v = o[keys[i]];
    if (typeof v === 'string' && v) return v;
    if (Array.isArray(v) && v.length) {
      var f = v[0];
      if (typeof f === 'string' && f) return f;
      if (f && typeof f.name === 'string') return f.name;
    }
  }
  return '';
}

// SPlayer 的两种时间口径：进度事件是毫秒，song-info 是秒（用 duration 判：
// 一首歌的"秒"不会超过 10 小时）。
function timeToMs(v, duration) {
  if (v < 0) return 0;
  if (duration > 36000) return Math.round(v);
  if (duration > 0) return Math.round(v * 1000);
  return v !== Math.floor(v) || v < 2000 ? Math.round(v * 1000) : Math.round(v);
}

// AMLL 的 LyricLine：{ words:[{word}], startTime, endTime }（毫秒）。
// 也容忍 {time,text} 与原始 LRC 行；全认不出来就返回 null（保持原歌词）。
function parseLines(raw) {
  if (!Array.isArray(raw) || !raw.length) return null;
  var out = [];
  for (var i = 0; i < raw.length; i++) {
    var it = raw[i];
    if (typeof it === 'string') {
      var m = /^\[(\d+):(\d+(?:[.:]\d+)?)\]\s*(.*)$/.exec(it.trim());
      if (m) {
        out.push({
          start: parseInt(m[1], 10) * 60000 + Math.round(parseFloat(m[2].replace(':', '.')) * 1000),
          text: m[3].trim()
        });
      }
      continue;
    }
    if (!it || typeof it !== 'object') continue;
    var text = lineText(it);
    var start = null, ms = true;
    if (typeof it.startTime === 'number') start = it.startTime;
    else if (typeof it.time === 'number') { start = it.time; ms = it.time > 3600; }
    if (start === null) continue;
    out.push({ start: Math.round(ms ? start : start * 1000), text: String(text || '').trim() });
  }
  if (!out.length) return null;
  out.sort(function (a, b) { return a.start - b.start; });
  return out;
}

function lineText(it) {
  if (Array.isArray(it.words) && it.words.length) {
    var s = '';
    for (var i = 0; i < it.words.length; i++) {
      var w = it.words[i];
      if (w && typeof w.word === 'string') s += w.word;
    }
    if (s) return s;
  }
  return it.text || it.lineLyric || it.lrc || it.content || it.lyric || '';
}

// 当前这一句 + 紧随其后的一句（都跳过空行）。
function lineAt(posMs) {
  var lines = sp.lines;
  if (!lines.length) return { line: '', next: '' };
  var idx = -1;
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].start <= posMs) idx = i; else break;
  }
  var cur = '';
  if (idx < 0) {
    cur = '';
  } else {
    cur = lines[idx].text;
    // 间奏（空行）：沿用上一句，免得歌词区闪空
    for (var j = idx; j >= 0 && !cur; j--) cur = lines[j].text;
  }
  var next = '';
  for (var k = idx + 1; k < lines.length; k++) { if (lines[k].text) { next = lines[k].text; break; } }
  return { line: cur, next: next };
}

function spPos() {
  if (!sp.playing || !sp.posAt) return sp.posMs;
  var d = Date.now() - sp.posAt;
  if (d < 0 || d > 5000) return sp.posMs;
  return sp.posMs + d;
}

// 连接状态写进自己的存储，WebUI 读它显示"已连接/未连接"。
function publishStatus() {
  var l = lineAt(spPos());
  var s = JSON.stringify({
    connected: spConnected(),
    playing: sp.playing,
    title: sp.title,
    artist: sp.artist,
    line: l.line || l.next,
    error: sp.error,
    port: sp.port,
    at: Math.floor(Date.now() / 1000)
  });
  if (s === spLastState) return;
  spLastState = s;
  try { kit.storage.setItem('splayerState', JSON.parse(s)).catch(function () {}); } catch (e) {}
}

// ---- 外观设置 ----

function getNum(key, def) {
  return kit.storage.getItem(key).then(function (v) {
    var n = typeof v === 'number' ? v : parseFloat(v);
    return isFinite(n) ? n : def;
  }).catch(function () { return def; });
}

function readConfig() {
  return Promise.all([
    getNum('fontScale', 1), getNum('blur', 26), getNum('dim', 0.3),
    kit.storage.getItem('splayerLyrics').catch(function () { return null; }),
    getNum('splayerPort', DEFAULT_PORT)
  ]).then(function (r) {
    return {
      fontScale: Math.min(2, Math.max(0.6, r[0])),
      blur: Math.min(60, Math.max(0, r[1])),
      dim: Math.min(1, Math.max(0.05, r[2])),
      lyrics: !!r[3],
      port: (r[4] > 0 && r[4] < 65536) ? Math.round(r[4]) : DEFAULT_PORT
    };
  });
}

// ---- 文本宽度估算（单行省略）----
// 容器上的 text-overflow 在部分固件上只裁不加点，所以在插件里先截一次。

function charWidth(c) {
  var cp = c.charCodeAt(0);
  var wide = (cp >= 0x1100 && cp <= 0x115f) || (cp >= 0x2e80 && cp <= 0xa4cf) ||
    (cp >= 0xac00 && cp <= 0xd7a3) || (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe30 && cp <= 0xfe6f) || (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) || cp >= 0x1f300;
  if (wide) return 1;
  if (cp === 0x20) return 0.3;
  return 0.55;
}

function elide(s, maxW, fontPx) {
  s = String(s == null ? '' : s);
  if (!(fontPx > 0)) return s;
  maxW -= 2;
  var total = 0, i;
  for (i = 0; i < s.length; i++) total += charWidth(s[i]) * fontPx;
  if (total <= maxW) return s;
  var ell = charWidth('…') * fontPx;
  var out = '', used = 0;
  for (i = 0; i < s.length; i++) {
    var w = charWidth(s[i]) * fontPx;
    if (used + w + ell > maxW) break;
    used += w;
    out += s[i];
  }
  return out.replace(/\s+$/, '') + '…';
}

function oneLine(text, width, size, weight, color) {
  return {
    type: 'text',
    props: { text: elide(text, width, size) },
    style: {
      width: width, fontSize: size, fontWeight: weight, color: color,
      lines: 1, textOverflow: 'ellipsis'
    }
  };
}

function trackKey(title, artist) {
  var seed = (title || '') + '|' + (artist || '');
  var h = 5381;
  for (var i = 0; i < seed.length; i++) h = ((h * 33) ^ seed.charCodeAt(i)) >>> 0;
  return h.toString(16);
}

// ---- 视图 ----

function buildTree(screen, cfg, music, lyric) {
  var sw = (screen && screen.width > 0) ? screen.width : DESIGN;
  var sh = (screen && screen.height > 0) ? screen.height : DESIGN;
  var shape = (screen && screen.shape) || '';
  var s = Math.min(sw, sh) / DESIGN;
  var f = function (base) { return Math.round(base * s * cfg.fontScale); };

  var title = music.title || '';
  var artist = music.artist || '';
  var hasMedia = !!music.hasMedia;
  // 宿主给的 artUrl 是手表可达的封面地址（kit.music.now 的 artUrl / artBlur）
  var art = hasMedia ? (music.artUrl || '') : '';
  var key = trackKey(title, artist);
  var withQuery = function (u, q) { return u + (u.indexOf('?') >= 0 ? '&' : '?') + q; };
  var background = art
    ? withQuery(art, 'w=' + Math.round(sw) + '&h=' + Math.round(sh) +
      '&size=' + Math.round(Math.max(sw, sh)) + '&blur=' + Math.round(cfg.blur) +
      '&dim=' + (Math.round(cfg.dim * 100) / 100) + '&k=' + key)
    : '';
  var coverSrc = art ? withQuery(art, 'size=' + Math.round(sw) + '&k=' + key) : '';

  // 背景图**不走 CSS background-image**：手表端对远程 URL 的 CSS 背景图渲染不可靠
  // （实测同一时刻：封面 <image> 能出、CSS 背景出不来；Vela 文档里背景图示例也全是
  // 本地资源）。可靠路径是 <image> 节点（.flat-img 自带 object-fit: cover），铺在最底层。
  // 坐标按引擎的 480 基准宽；left/top 取负值抵消根容器的内边距，保证铺满整屏。
  var rootStyle = { backgroundColor: '#0b0d12' };
  var bgNode = background ? {
    type: 'image',
    props: { src: background },
    style: { position: 'absolute', left: -80, top: -80, width: 640, height: 640 }
  } : null;

  var titleText = title || 'HydroLink';

  if (!cfg.lyrics) {
    // ---------- 原版式 ----------
    var canvasW = 480, canvasH = (sw > 0 && sh > 0) ? sh * (480 / sw) : 480;
    var isRect = shape === 'rect';
    var coverSize = isRect ? 290 : 200;
    var coverStyle = { width: coverSize, height: coverSize, borderRadius: 16 };
    if (isRect) {
      coverStyle.position = 'absolute';
      coverStyle.left = Math.round((canvasW - coverSize) / 2);
      coverStyle.top = Math.round((canvasH - coverSize) / 2 + 26);
    } else {
      coverStyle.marginTop = 18;
    }
    var cover;
    if (coverSrc) {
      cover = { type: 'image', props: { src: coverSrc }, events: { tap: 'toggle' }, style: coverStyle };
    } else if (hasMedia) {
      cover = { type: 'text', props: { text: '♪' },
        style: { fontSize: 64, color: '#3a4150', textAlign: 'center', width: '100%', marginTop: 30 } };
    } else {
      cover = { type: 'text', props: { text: '无正在播放的媒体' },
        style: { fontSize: 22, color: '#8b93a3', textAlign: 'center', width: '100%', marginTop: 40 } };
    }
    var style = {};
    for (var k in rootStyle) style[k] = rootStyle[k];
    style.padding = 16;
    style.alignItems = 'center';
    return {
      refreshMs: CLASSIC_REFRESH_MS,
      root: {
        type: 'scroll',
        style: style,
        children: (bgNode ? [bgNode] : []).concat([
          { type: 'text', props: { text: clockText() },
            style: { fontSize: f(36), fontWeight: 700, color: '#FFFFFF', textAlign: 'center', width: '100%' } },
          { type: 'text', props: { text: titleText },
            style: { fontSize: f(32), fontWeight: 700, color: '#FFFFFF', textAlign: 'center', width: '100%', marginTop: 10 } },
          { type: 'text', props: { text: artist },
            style: { fontSize: f(22), color: 'rgba(255,255,255,0.4)', textAlign: 'center', width: '100%', marginTop: 4 } },
          cover
        ])
      }
    };
  }

  // ---------- 设计稿版式（内容盒坐标）----------
  var insetX = shape === 'circle' ? SAFE_INSET_X : 0;
  var insetTop = shape === 'circle' ? SAFE_INSET_TOP : 0;
  var canvasW2 = sw - 2 * insetX;

  var coverSize2 = Math.round(D_COVER * s);
  var coverLeft = Math.round(D_COVER_LEFT * s) - insetX;
  var gap = Math.round(D_GAP * s);
  var textLeft = coverLeft + coverSize2 + gap;
  var textRight = Math.round(D_RIGHT_PAD * s) - insetX;
  var textWidth = Math.round(canvasW2 - textLeft - textRight);
  var top = Math.round((sh - coverSize2) / 2) - insetTop;

  var coverNode;
  if (coverSrc) {
    coverNode = { type: 'image', props: { src: coverSrc }, events: { tap: 'toggle' },
      style: { width: coverSize2, height: coverSize2, borderRadius: Math.round(D_RADIUS * s) } };
  } else {
    coverNode = { type: 'text', props: { text: '♪' },
      style: { width: coverSize2, fontSize: Math.round(coverSize2 * 0.4), color: '#3a4150',
        textAlign: 'center', marginTop: Math.round(coverSize2 * 0.28) } };
  }

  var textKids = [
    oneLine(titleText, textWidth, f(32), 700, '#FFFFFF'),
    oneLine(artist || (hasMedia ? '' : '无正在播放的媒体'), textWidth, f(22), 400, 'rgba(255,255,255,0.4)')
  ];
  if (lyric && lyric.text) {
    textKids.push({
      type: 'text',
      props: { text: lyric.text },
      style: {
        width: textWidth, fontSize: f(26), lineHeight: f(32),
        color: lyric.upcoming ? 'rgba(255,255,255,0.45)' : '#FFFFFF',
        marginTop: Math.round(5 * s), lines: 3, textOverflow: 'ellipsis'
      }
    });
  }

  return {
    refreshMs: LYRICS_REFRESH_MS,
    root: {
      type: 'column',
      style: rootStyle,
      children: (bgNode ? [bgNode] : []).concat([
        { type: 'text', props: { text: clockText() },
          style: { position: 'absolute', top: Math.round(D_CLOCK_TOP * s) - insetTop, left: 0, right: 0,
            width: Math.round(canvasW2), fontSize: f(36), fontWeight: 700, color: '#FFFFFF', textAlign: 'center' } },
        { type: 'row',
          style: { position: 'absolute', top: top, left: coverLeft,
            width: coverSize2 + gap + textWidth, alignItems: 'flex-start' },
          children: [
            coverNode,
            { type: 'column', style: { width: textWidth, marginLeft: gap, alignItems: 'flex-start' },
              children: textKids }
          ] }
      ])
    }
  };
}

function clockText() {
  var d = new Date();
  return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
}

module.exports = {
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null;
    var r = req || {};
    var ev = r.event || {};

    // 封面点击：切播放/暂停（JS 插件直接调 kit，不需要 effects）
    if (ev.handler === 'toggle') {
      try { await kit.music.action('playpause'); } catch (e) {}
      try { await kit.storage.setItem('lastToggleAt', Math.floor(Date.now() / 1000)); } catch (e) {}
    }

    var cfg = await readConfig();
    // 开关打开就维持连接；关掉立即断开（网络是我们自己的事，宿主不掺和）
    if (cfg.lyrics) spConnect(cfg.port); else if (sp.state !== 'closed') spClose();

    var music = {};
    try { music = (await kit.music.now()) || {}; } catch (e) { music = {}; }

    // 系统会话优先：SPlayer 在 Linux 注册 MPRIS、Windows 注册 SMTC，正常情况下
    // 上面 kit.music.now() 就已经有歌名和封面了。只有系统侧读不到（播放器没注册
    // 会话，或会话被别的播放器抢走）才用 SPlayer 的曲目兜底，且**不**给封面地址
    // —— 宿主的 /api/music/art 只认系统会话的封面，给个取不到的地址只会白铺一张黑图。
    if (!music.hasMedia && spConnected() && sp.title) {
      music = { hasMedia: true, title: sp.title, artist: sp.artist, playing: sp.playing, artUrl: '' };
    }

    // 歌词：把当前这一句给视图；前奏（还没唱到第一句）时给下一句并压暗
    var lyric = null;
    if (cfg.lyrics) {
      var l = lineAt(spPos());
      if (l.line) lyric = { text: l.line, upcoming: false };
      else if (l.next) lyric = { text: l.next, upcoming: true };
    }

    return buildTree(r.screen || {}, cfg, music, lyric);
  }
};

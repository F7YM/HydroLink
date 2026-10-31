// HydroPlugin: OpenCode 控制器 (com.float11.opencode.ctrl)
// 对接 OpenCode V2 HTTP API (https://opencode.ai/v2/docs/api)
// 1. 严禁包含 Emoji（Vela 系统不支持显示 Emoji，全局 safeText 过滤）
// 2. 顶栏与 HydroBili 对齐，状态以单个圆点显示
// 3. 会话内消息按时间正序排列，默认定位最底部，支持触顶懒加载更早消息

var PKG = 'com.float11.opencode.ctrl';
var CONFIG_KEY = 'opencode_config';
var PAGE_SIZE = 20;

var DOT_IDLE = '#4ADE80';    // 在线 / 空闲（绿）
var DOT_RUNNING = '#38BDF8'; // 运行中（亮蓝）
var DOT_OFFLINE = '#F87171'; // 未连接 / 异常（红）

// 内存状态：当前打开的会话 ID、消息懒加载缓存、可用主 Agent 缓存、折叠展开状态
var currentSessionId = '';
var sessionMsgCache = {}; // sessionId -> { list: [], cursor: null, fallbackLimit: 20, hasMore: false, session: null, isRunning: false, perms: [] }
var primaryAgentsCache = null;
var expandedToolGroups = {}; // groupId -> boolean
var expandedMetaTurns = {};  // turnId -> boolean

function safeText(value) {
  return String(value == null ? '' : value)
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, ' ')
    .trim();
}

function base64Encode(str) {
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(str, 'utf-8').toString('base64');
  }
  if (typeof btoa === 'function') {
    try {
      return btoa(str);
    } catch (e) {
      if (typeof TextEncoder !== 'undefined') {
        var u8 = new TextEncoder().encode(str);
        var bin = '';
        for (var i = 0; i < u8.length; i++) {
          bin += String.fromCharCode(u8[i]);
        }
        return btoa(bin);
      }
    }
  }
  return '';
}

function normalizeBaseUrl(raw) {
  var s = String(raw || '').trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) {
    s = 'http://' + s;
  }
  return s.replace(/\/+$/, '');
}

function formatTime(tsMs) {
  var n = Number(tsMs);
  if (!n || n <= 0) return '';
  if (n < 10000000000) n = n * 1000;
  var d = new Date(n);
  var now = new Date();
  var hh = ('0' + d.getHours()).slice(-2);
  var mm = ('0' + d.getMinutes()).slice(-2);
  if (d.toDateString() === now.toDateString()) {
    return hh + ':' + mm;
  }
  var mo = ('0' + (d.getMonth() + 1)).slice(-2);
  var da = ('0' + d.getDate()).slice(-2);
  return mo + '-' + da + ' ' + hh + ':' + mm;
}

function shortDir(dir) {
  if (!dir || typeof dir !== 'string') return '';
  var clean = dir.replace(/[\\/]+$/, '');
  var parts = clean.split(/[\\/]/);
  return safeText(parts[parts.length - 1] || clean);
}

// 统一请求封装：优先通过同源 /api/hydro/proxy 发起以规避 WebView CORS 与受限请求头
async function ocRequest(cfg, path, opts) {
  opts = opts || {};
  var baseUrl = normalizeBaseUrl(cfg && cfg.baseUrl);
  if (!baseUrl) {
    throw new Error('尚未配置 OpenCode 地址');
  }
  var targetUrl = baseUrl + (path.startsWith('/') ? path : ('/' + path));
  var headers = {
    'Accept': 'application/json'
  };
  var user = (cfg && cfg.username) ? String(cfg.username).trim() : 'opencode';
  var pass = (cfg && (cfg.password || cfg.token)) ? String(cfg.password || cfg.token).trim() : '';
  if (pass) {
    var authVal = 'Basic ' + base64Encode(user + ':' + pass);
    headers['Authorization'] = authVal;
    headers['X-Proxy-Authorization'] = authVal;
  }
  if (cfg && cfg.directory && String(cfg.directory).trim()) {
    headers['x-opencode-directory'] = String(cfg.directory).trim();
  }
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
  }
  if (opts.headers) {
    Object.assign(headers, opts.headers);
  }

  var method = opts.method || 'GET';
  var bodyStr = opts.body !== undefined
    ? (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body))
    : undefined;

  var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var timeoutMs = opts.timeout || 8000;
  var timer = null;

  try {
    var op = (async function () {
      var fetchOpts = {
        method: method,
        headers: headers,
        body: bodyStr,
        signal: controller ? controller.signal : undefined
      };
      var res;
      try {
        // 优先直连（本地环回/同局域网 OpenCode 原生支持 CORS 与 Basic Auth）
        res = await fetch(targetUrl, fetchOpts);
      } catch (directErr) {
        // 仅当直连抛出跨域或网络受限异常时，降级走同源代理
        if (typeof location !== 'undefined' && location.origin && /^https?:/i.test(location.origin)) {
          var proxyUrl = location.origin.replace(/\/$/, '') + '/api/hydro/proxy?url=' + encodeURIComponent(targetUrl);
          res = await fetch(proxyUrl, fetchOpts);
        } else {
          throw directErr;
        }
      }

      if (res.status === 401 || res.status === 403) {
        throw new Error('认证失败（HTTP ' + res.status + '），请在插件界面检查密码或重新配对');
      }
      if (res.status === 204) return null;
      var text = await res.text();
      var data = null;
      if (text) {
        try {
          data = JSON.parse(text);
        } catch (e) {
          data = null;
        }
      }
      if (!res.ok) {
        var errMsg = (data && (data.message || data.error)) || ('HTTP ' + res.status);
        throw new Error(safeText(errMsg));
      }
      return data;
    })();

    return await Promise.race([
      op,
      new Promise(function (_, reject) {
        timer = setTimeout(function () {
          if (controller) controller.abort();
          reject(new Error('连接 OpenCode 超时'));
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function unwrapList(res) {
  if (Array.isArray(res)) return res;
  if (res && Array.isArray(res.data)) return res.data;
  return [];
}

function unwrapData(res) {
  if (res && typeof res.data === 'object' && res.data !== null && !Array.isArray(res.data)) {
    return res.data;
  }
  if (res && typeof res === 'object' && !Array.isArray(res)) {
    return res;
  }
  return {};
}

async function fetchActiveMap(cfg) {
  try {
    var res = await ocRequest(cfg, '/api/session/active');
    return unwrapData(res);
  } catch (e) {}
  return {};
}

async function fetchPermissions(cfg, sessionId) {
  try {
    var path = sessionId
      ? ('/api/session/' + encodeURIComponent(sessionId) + '/permission')
      : '/api/permission/request';
    var res = await ocRequest(cfg, path);
    return unwrapList(res);
  } catch (e) {
    return [];
  }
}

async function fetchPrimaryAgents(cfg) {
  if (primaryAgentsCache && (Date.now() - primaryAgentsCache.time < 120000)) {
    return primaryAgentsCache.list;
  }
  try {
    var res = await ocRequest(cfg, '/api/agent');
    var all = unwrapList(res);
    var list = all.filter(function (a) {
      return a && a.id && !a.hidden && (a.mode === 'primary' || a.mode === 'all');
    }).map(function (a) {
      return a.id;
    });
    if (list.length) {
      primaryAgentsCache = { list: list, time: Date.now() };
      return list;
    }
  } catch (e) {}
  return ['build', 'plan'];
}

// ---------------------------------------------------------------- 消息分页与懒加载
function mergeMessagesChronological(existingAsc, incomingAsc, prependOlder) {
  var map = {};
  var order = [];
  var firstList = prependOlder ? incomingAsc : existingAsc;
  var secondList = prependOlder ? existingAsc : incomingAsc;

  function addAll(arr) {
    if (!Array.isArray(arr)) return;
    for (var i = 0; i < arr.length; i++) {
      var m = arr[i];
      if (!m || !m.id) continue;
      if (!map[m.id]) {
        order.push(m.id);
      }
      map[m.id] = m;
    }
  }

  addAll(firstList);
  addAll(secondList);

  var merged = order.map(function (id) { return map[id]; });
  merged.sort(function (a, b) {
    var ta = (a.time && a.time.created) || 0;
    var tb = (b.time && b.time.created) || 0;
    return ta - tb;
  });
  return merged;
}

async function loadSessionMessages(cfg, sessionId, mode) {
  // mode: 'reset' (首次进入，拉最新一页), 'refresh' (拉最新一页并合并到底部), 'older' (向上懒加载更早一页)
  var state = sessionMsgCache[sessionId];
  if (!state || mode === 'reset') {
    state = { list: [], cursor: null, fallbackLimit: PAGE_SIZE, hasMore: false };
    sessionMsgCache[sessionId] = state;
  }

  if (mode === 'older') {
    if (state.cursor) {
      var cRes = await ocRequest(
        cfg,
        '/api/session/' + encodeURIComponent(sessionId) + '/message?limit=' + PAGE_SIZE + '&cursor=' + encodeURIComponent(state.cursor)
      );
      var olderDesc = unwrapList(cRes);
      var olderAsc = olderDesc.slice().reverse();
      state.list = mergeMessagesChronological(state.list, olderAsc, true);
      var nextCur = cRes && cRes.cursor && (cRes.cursor.next || cRes.cursor.previous) || null;
      state.cursor = nextCur;
      state.hasMore = !!(nextCur && olderDesc.length > 0);
      return state;
    } else {
      state.fallbackLimit = (state.fallbackLimit || PAGE_SIZE) + PAGE_SIZE;
      var fRes = await ocRequest(
        cfg,
        '/api/session/' + encodeURIComponent(sessionId) + '/message?limit=' + state.fallbackLimit + '&order=desc'
      );
      var fDesc = unwrapList(fRes);
      var fAsc = fDesc.slice().reverse();
      var prevCount = state.list.length;
      state.list = mergeMessagesChronological(state.list, fAsc, true);
      var fCur = fRes && fRes.cursor && (fRes.cursor.next || fRes.cursor.previous) || null;
      state.cursor = fCur;
      state.hasMore = !!(fCur || (fDesc.length >= state.fallbackLimit && state.list.length > prevCount));
      return state;
    }
  }

  // 'reset' 或 'refresh'：拉取最新 PAGE_SIZE 条（order=desc）
  var res = await ocRequest(
    cfg,
    '/api/session/' + encodeURIComponent(sessionId) + '/message?limit=' + PAGE_SIZE + '&order=desc'
  );
  var desc = unwrapList(res);
  var asc = desc.slice().reverse();
  if (mode === 'reset' || !state.list.length) {
    state.list = asc;
    var cur = res && res.cursor && (res.cursor.next || res.cursor.previous) || null;
    state.cursor = cur;
    state.hasMore = !!(cur || desc.length >= PAGE_SIZE);
  } else {
    state.list = mergeMessagesChronological(state.list, asc, false);
  }
  return state;
}

function shortPath(p) {
  if (!p || typeof p !== 'string') return '';
  var clean = p.replace(/[\\/]+$/, '');
  var parts = clean.split(/[\\/]/).filter(Boolean);
  if (parts.length <= 2) return safeText(clean);
  return safeText(parts.slice(-2).join('/'));
}

function firstCodeLine(code) {
  if (!code || typeof code !== 'string') return '';
  var lines = code.split(/\r?\n/).map(function (l) { return l.trim(); }).filter(function (l) {
    return l && !l.startsWith('//') && !l.startsWith('/*') && !l.startsWith('*');
  });
  return lines[0] || '';
}

function compactOneLine(raw, maxLen) {
  var limit = maxLen || 46;
  var s = safeText(String(raw == null ? '' : raw).replace(/\s+/g, ' '));
  if (!s) return '';
  if (s.length > limit) return s.slice(0, limit) + '…';
  return s;
}

// 从 OpenCode V2 tool part 中提取具体命令或目标内容
function extractToolDetail(p) {
  if (!p) return '';
  var name = String(p.name || p.tool || '').toLowerCase();
  var inp = (p.state && p.state.input) || p.input || p.args || {};
  var raw = '';

  if (typeof inp === 'string') {
    raw = inp;
  } else if (inp && typeof inp === 'object') {
    if (name === 'shell' || name === 'bash' || name === 'exec') {
      raw = inp.command || inp.cmd || inp.description || '';
    } else if (name === 'read' || name === 'write' || name === 'edit' || name === 'multiedit') {
      var sp = shortPath(inp.path || inp.filePath || inp.file || '');
      if (sp && inp.offset) {
        raw = sp + ':' + inp.offset;
      } else {
        raw = sp;
      }
    } else if (name === 'grep') {
      var pat = inp.pattern || inp.query || '';
      var gp = shortPath(inp.path || inp.include || '');
      raw = pat + (gp ? (' (' + gp + ')') : '');
    } else if (name === 'glob') {
      raw = inp.pattern || shortPath(inp.path || '') || '';
    } else if (name === 'execute') {
      raw = inp.description || firstCodeLine(inp.code) || '';
    } else if (name === 'subagent' || name === 'task') {
      raw = inp.description || inp.prompt || '';
    } else if (name === 'webfetch') {
      raw = inp.url || '';
    } else if (name === 'websearch') {
      raw = inp.query || '';
    } else {
      raw = inp.command || inp.cmd || shortPath(inp.path || inp.filePath || '') ||
        inp.pattern || inp.query || inp.url || inp.description || '';
      if (!raw) {
        var keys = Object.keys(inp);
        for (var k = 0; k < keys.length; k++) {
          var v = inp[keys[k]];
          if (typeof v === 'string' && v.trim()) {
            raw = v;
            break;
          }
        }
      }
    }
  }

  if (!raw && p.state && p.state.title) {
    raw = p.state.title;
  }
  return compactOneLine(raw, 48);
}

// 解析单条消息的有序分片（严格保持 text 与 tool 的真实时间先后顺序）
function parseMessageSummary(msg) {
  if (!msg) return { role: 'assistant', orderedParts: [], error: '' };
  var type = msg.type || msg.role || 'assistant';
  var created = (msg.time && msg.time.created) ? formatTime(msg.time.created) : '';

  if (type === 'user') {
    var uText = safeText(msg.text || '');
    if (!uText && Array.isArray(msg.content)) {
      uText = msg.content.map(function (p) { return safeText(p && p.text || ''); }).filter(Boolean).join('\n');
    }
    return {
      id: msg.id || '',
      role: 'user',
      agent: '你',
      time: created,
      orderedParts: [{ kind: 'text', text: uText || '[空消息]' }],
      hasReasoning: false,
      error: ''
    };
  }

  if (type === 'assistant') {
    var parts = Array.isArray(msg.content) ? msg.content : (Array.isArray(msg.parts) ? msg.parts : []);
    var orderedParts = [];
    var hasReasoning = false;

    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (!p) continue;
      if (p.type === 'text' && p.text) {
        var cleaned = safeText(p.text);
        if (cleaned) {
          orderedParts.push({ kind: 'text', text: cleaned });
        }
      } else if (p.type === 'reasoning') {
        hasReasoning = true;
      } else if (p.type === 'tool') {
        var tName = safeText(p.name || p.tool || 'tool');
        var st = (p.state && p.state.status) || (p.executed ? 'completed' : 'running');
        var detail = extractToolDetail(p);
        orderedParts.push({
          kind: 'tool',
          id: p.id || ('tool_' + i),
          name: tName,
          status: st,
          detail: detail
        });
      }
    }

    var errStr = '';
    if (msg.error) {
      errStr = safeText(msg.error.message || (msg.error.data && msg.error.data.message) || msg.error.name || '执行出错');
    }

    var agentLabel = safeText(msg.agent || 'AI');
    return {
      id: msg.id || '',
      role: 'assistant',
      agent: agentLabel,
      time: created,
      orderedParts: orderedParts,
      hasReasoning: hasReasoning,
      error: errStr
    };
  }

  // 系统 / 切换事件等轻量消息
  var sysText = safeText(msg.text || type);
  return {
    id: msg.id || '',
    role: 'system',
    agent: '',
    time: created,
    orderedParts: sysText ? [{ kind: 'text', text: sysText }] : [],
    hasReasoning: false,
    error: ''
  };
}

function mergeToolStatus(items) {
  var hasRunning = false;
  var hasError = false;
  for (var i = 0; i < items.length; i++) {
    var st = items[i].status;
    if (st === 'running' || st === 'streaming' || st === 'pending') hasRunning = true;
    if (st === 'error') hasError = true;
  }
  if (hasRunning) return 'running';
  if (hasError) return 'error';
  return 'completed';
}

// 按真实先后顺序构建块流：仅将连续相邻的同类工具原位折叠，工具与文本严格按实际发生顺序排列
function buildOrderedBlocks(orderedParts, turnId) {
  var blocks = [];
  for (var i = 0; i < orderedParts.length; i++) {
    var p = orderedParts[i];
    if (!p) continue;
    var last = blocks.length ? blocks[blocks.length - 1] : null;
    if (p.kind === 'tool') {
      if (last && last.kind === 'tool_group' && last.name === p.name) {
        last.items.push(p);
      } else {
        blocks.push({
          kind: 'tool_group',
          id: turnId + '_tg_' + blocks.length,
          name: p.name || 'tool',
          items: [p]
        });
      }
    } else if (p.kind === 'text' && p.text) {
      if (last && last.kind === 'text') {
        last.text = last.text + '\n' + p.text;
      } else {
        blocks.push({
          kind: 'text',
          id: turnId + '_txt_' + blocks.length,
          text: p.text
        });
      }
    }
  }

  for (var j = 0; j < blocks.length; j++) {
    var b = blocks[j];
    if (b.kind === 'tool_group') {
      var latestDetail = '';
      for (var k = b.items.length - 1; k >= 0; k--) {
        if (b.items[k].detail) {
          latestDetail = b.items[k].detail;
          break;
        }
      }
      b.status = mergeToolStatus(b.items);
      b.latestDetail = latestDetail;
    } else if (b.kind === 'text') {
      var trimmed = b.text.trim();
      if (trimmed.length > 360) {
        trimmed = trimmed.slice(0, 360) + '…';
      }
      b.text = trimmed;
    }
  }
  return blocks;
}

// 将连续的 assistant 步骤消息归并为单轮对话卡片，内部严格按时间先后顺序排列工具与回复文本
function buildConversationTurns(list) {
  var rawTurns = [];
  for (var i = 0; i < list.length; i++) {
    var parsed = parseMessageSummary(list[i]);
    if (parsed.role === 'system') continue;

    var prev = rawTurns.length ? rawTurns[rawTurns.length - 1] : null;
    if (parsed.role === 'assistant' && prev && prev.role === 'assistant') {
      for (var p = 0; p < parsed.orderedParts.length; p++) {
        prev.orderedParts.push(parsed.orderedParts[p]);
      }
      if (parsed.error) prev.errors.push(parsed.error);
      if (parsed.hasReasoning) prev.hasReasoning = true;
      if (parsed.agent) prev.agent = parsed.agent;
      if (parsed.time) prev.endTime = parsed.time;
    } else {
      var turnId = parsed.id || ('turn_' + i);
      rawTurns.push({
        id: turnId,
        role: parsed.role,
        agent: parsed.agent,
        startTime: parsed.time,
        endTime: parsed.time,
        orderedParts: parsed.orderedParts ? parsed.orderedParts.slice() : [],
        errors: parsed.error ? [parsed.error] : [],
        hasReasoning: !!parsed.hasReasoning
      });
    }
  }

  return rawTurns.map(function (turn) {
    var blocks = buildOrderedBlocks(turn.orderedParts, turn.id);
    var errText = turn.errors.join('；');
    if (!blocks.length && !errText) {
      blocks.push({
        kind: 'text',
        id: turn.id + '_txt_0',
        text: turn.hasReasoning ? '思考中…' : '正在生成…'
      });
    }
    var timeLabel = turn.endTime || turn.startTime || '';
    var metaHeader = turn.role === 'user'
      ? ('你' + (timeLabel ? (' · ' + timeLabel) : ''))
      : ((turn.agent || 'AI') + (timeLabel ? (' · ' + timeLabel) : ''));

    return {
      id: turn.id,
      role: turn.role,
      metaHeader: metaHeader,
      blocks: blocks,
      error: errText
    };
  });
}

// ---------------------------------------------------------------- UI 树辅助构建
function text(content, size, color, extraStyle) {
  return {
    type: 'text',
    props: { text: safeText(content) },
    style: Object.assign({
      fontSize: size || 20,
      color: color || '#FFFFFF',
      marginBottom: 8
    }, extraStyle || {})
  };
}

function button(label, tap, bg, extraStyle) {
  return {
    type: 'button',
    props: { label: safeText(label) },
    events: { tap: tap || 'refresh' },
    style: Object.assign({
      width: '100%',
      height: 46,
      backgroundColor: bg || '#3B82F6',
      color: '#FFFFFF',
      borderRadius: 23,
      marginBottom: 10
    }, extraStyle || {})
  };
}

function statusDotNode(color, size) {
  var d = size || 10;
  return {
    type: 'box',
    style: {
      width: d,
      height: d,
      minWidth: d,
      minHeight: d,
      borderRadius: Math.floor(d / 2),
      backgroundColor: color || DOT_IDLE,
      marginRight: 8,
      flexShrink: 0,
      'flex-shrink': 0
    }
  };
}

function permissionCardNode(perm) {
  var sid = perm.sessionID || '';
  var pid = perm.id || '';
  var actionName = safeText(perm.action || '权限请求');
  var resList = Array.isArray(perm.resources) ? perm.resources.map(safeText).filter(Boolean).join(', ') : '';
  var detail = safeText(perm.message || resList || '需要确认操作').slice(0, 100);

  return {
    id: 'perm_' + pid,
    type: 'column',
    style: {
      padding: 12,
      backgroundColor: '#2B1F1D',
      borderRadius: 16,
      marginBottom: 12
    },
    children: [
      {
        type: 'text',
        props: { text: '待审批: ' + actionName },
        style: { fontSize: 20, fontWeight: 'bold', color: '#FBBF24', marginBottom: 4 }
      },
      {
        type: 'text',
        props: { text: detail },
        style: { fontSize: 16, color: '#E5E7EB', marginBottom: 10 }
      },
      {
        type: 'row',
        style: { width: '100%', justifyContent: 'space-between', alignItems: 'center' },
        children: [
          {
            type: 'button',
            props: { label: '允许' },
            events: { tap: 'perm_reply:' + sid + ':' + pid + ':once' },
            style: { flex: 1, height: 40, backgroundColor: '#10B981', color: '#FFFFFF', borderRadius: 20, marginRight: 6 }
          },
          {
            type: 'button',
            props: { label: '始终' },
            events: { tap: 'perm_reply:' + sid + ':' + pid + ':always' },
            style: { flex: 1, height: 40, backgroundColor: '#3B82F6', color: '#FFFFFF', borderRadius: 20, marginRight: 6 }
          },
          {
            type: 'button',
            props: { label: '拒绝' },
            events: { tap: 'perm_reply:' + sid + ':' + pid + ':reject' },
            style: { flex: 1, height: 40, backgroundColor: '#EF4444', color: '#FFFFFF', borderRadius: 20 }
          }
        ]
      }
    ]
  };
}

// 与 HydroBili 对齐的顶栏与页面包装函数（底部悬浮按钮为新建会话）
function listViewTree(children, dotColor, refreshMs, showFab) {
  var title = '会话列表';
  var fabConfig = showFab === false ? null : {
    icon: '/common/icons/plus.png',
    tap: 'new_session',
    background: '#3B82F6'
  };
  return {
    title: title,
    refreshMs: refreshMs || 0,
    fab: fabConfig,
    root: {
      type: 'scroll',
      props: {
        title: title,
        fab: fabConfig
      },
      style: { padding: 18, backgroundColor: '#000000' },
      media: {
        circle: { paddingLeft: 42, paddingRight: 42, paddingTop: 16, paddingBottom: 120 },
        pill: { padding: 14 }
      },
      children: children
    }
  };
}

function sessionDetailViewTree(session, msgState, isRunning, perms, errText) {
  var sid = session.id;
  var rawTitle = safeText(session.title || '新会话').slice(0, 14) || '会话';
  var header = {
    title: '‹' + rawTitle,
    tap: 'back_to_list'
  };

  var children = [];

  // 1. 顶部懒加载入口（存在更早历史消息时显示）
  if (msgState && msgState.hasMore) {
    children.push({
      id: 'btn_load_older_' + sid,
      type: 'button',
      props: { label: '加载更早消息' },
      events: { tap: 'load_older:' + sid },
      style: {
        width: '100%',
        height: 38,
        backgroundColor: '#161820',
        color: '#94A3B8',
        borderRadius: 19,
        marginBottom: 12
      }
    });
  }

  // 2. 消息列表（按时间正序：旧消息在上，最新消息在下；同轮步骤归并，同类工具与时间模式折叠）
  var list = (msgState && Array.isArray(msgState.list)) ? msgState.list : [];
  var turns = buildConversationTurns(list);
  if (!turns.length) {
    children.push({
      id: 'empty_msg_' + sid,
      type: 'column',
      style: {
        padding: 18,
        backgroundColor: '#14161E',
        borderRadius: 16,
        marginBottom: 12,
        alignItems: 'center'
      },
      children: [
        text('暂无消息，在下方输入指令开始对话', 18, '#94A3B8', { marginBottom: 0, textAlign: 'center' })
      ]
    });
  } else {
    for (var i = 0; i < turns.length; i++) {
      var turn = turns[i];
      var isUser = turn.role === 'user';
      var cardChildren = [];
      var metaExpanded = !!expandedMetaTurns[turn.id];
      var metaTapEv = { tap: 'toggle_meta:' + sid + ':' + turn.id };

      // 时间和模式默认折叠，点击消息卡片时展开显示
      if (metaExpanded && turn.metaHeader) {
        cardChildren.push({
          id: 'meta_' + turn.id,
          type: 'text',
          props: { text: turn.metaHeader },
          events: metaTapEv,
          style: {
            fontSize: 18,
            color: isUser ? '#93C5FD' : '#94A3B8',
            marginBottom: 6
          }
        });
      }

      var blocks = Array.isArray(turn.blocks) ? turn.blocks : [];
      for (var bIdx = 0; bIdx < blocks.length; bIdx++) {
        var blk = blocks[bIdx];
        var isLastBlock = (bIdx === blocks.length - 1) && !turn.error;

        if (blk.kind === 'text') {
          cardChildren.push({
            id: blk.id,
            type: 'text',
            props: { text: blk.text },
            events: metaTapEv,
            style: {
              fontSize: 23,
              color: '#FFFFFF',
              lineHeight: 30,
              marginBottom: isLastBlock ? 0 : 8
            }
          });
        } else if (blk.kind === 'tool_group') {
          var g = blk;
          var stSuffix = g.status === 'running'
            ? ' · 运行中'
            : (g.status === 'error' ? ' · 失败' : '');
          var stColor = g.status === 'error'
            ? '#FCA5A5'
            : (g.status === 'running' ? '#38BDF8' : '#86EFAC');

          if (g.items.length <= 1) {
            var single = g.items[0] || {};
            var line = '[' + g.name + stSuffix + ']' + (single.detail ? (' ' + single.detail) : '');
            cardChildren.push({
              id: 'tg_' + g.id,
              type: 'text',
              props: { text: line },
              events: metaTapEv,
              style: {
                fontSize: 18,
                color: stColor,
                marginBottom: isLastBlock ? 0 : 6
              }
            });
          } else {
            var tgExpanded = !!expandedToolGroups[g.id];
            var tgTapEv = { tap: 'toggle_tool:' + sid + ':' + g.id };
            if (!tgExpanded) {
              var foldedLine = '→ [' + g.name + ' · ' + g.items.length + stSuffix + ']' +
                (g.latestDetail ? (' ' + g.latestDetail) : '');
              cardChildren.push({
                id: 'tg_' + g.id,
                type: 'text',
                props: { text: foldedLine },
                events: tgTapEv,
                style: {
                  fontSize: 18,
                  color: stColor,
                  marginBottom: isLastBlock ? 0 : 6
                }
              });
            } else {
              cardChildren.push({
                id: 'tg_' + g.id + '_hdr',
                type: 'text',
                props: { text: '↓ [' + g.name + ' · ' + g.items.length + stSuffix + ']' },
                events: tgTapEv,
                style: {
                  fontSize: 18,
                  color: stColor,
                  marginBottom: 4
                }
              });
              for (var k = 0; k < g.items.length; k++) {
                var item = g.items[k];
                var itemCmd = item.detail || item.name;
                var isLastItem = (k === g.items.length - 1);
                cardChildren.push({
                  id: 'tg_' + g.id + '_it_' + k,
                  type: 'text',
                  props: { text: '  ' + (k + 1) + '. ' + itemCmd },
                  events: tgTapEv,
                  style: {
                    fontSize: 17,
                    color: '#CBD5E1',
                    marginBottom: (isLastItem && isLastBlock) ? 0 : (isLastItem ? 6 : 3)
                  }
                });
              }
            }
          }
        }
      }

      if (turn.error) {
        cardChildren.push({
          id: 'err_' + turn.id,
          type: 'text',
          props: { text: '错误: ' + turn.error },
          events: metaTapEv,
          style: { fontSize: 19, color: '#FCA5A5', marginBottom: 0 }
        });
      }

      children.push({
        id: 'msg_' + turn.id,
        type: 'column',
        style: {
          padding: 14,
          backgroundColor: isUser ? '#1E293B' : '#161820',
          borderRadius: 16,
          marginBottom: 12
        },
        events: metaTapEv,
        children: cardChildren
      });
    }
  }

  // 3. 待处理权限请求（若有）
  if (Array.isArray(perms) && perms.length) {
    for (var p = 0; p < perms.length; p++) {
      children.push(permissionCardNode(perms[p]));
    }
  }

  if (errText) {
    children.push(text(errText, 17, '#FCA5A5', { marginBottom: 8 }));
  }

  // 4. 底部输入与控制栏（默认滚到底部即停在此处；无压缩上下文按钮）
  children.push({
    id: 'input_prompt_' + sid,
    type: 'input',
    props: {
      value: '',
      placeholder: isRunning ? '追加指令…' : '点击输入指令…'
    },
    events: {
      change: 'send_prompt:' + sid
    },
    style: {
      width: '100%',
      height: 48,
      backgroundColor: '#1E2028',
      borderRadius: 24,
      marginBottom: 10
    }
  });

  var curAgent = safeText(session.agent || 'build');
  var actionRow = [
    {
      id: 'btn_agent_' + sid,
      type: 'button',
      props: { label: 'Agent: ' + curAgent },
      events: { tap: 'cycle_agent:' + sid + ':' + curAgent },
      style: {
        flex: 1,
        height: 42,
        backgroundColor: '#262936',
        color: '#E2E8F0',
        borderRadius: 21,
        marginRight: 8
      }
    }
  ];

  if (isRunning) {
    actionRow.push({
      id: 'btn_interrupt_' + sid,
      type: 'button',
      props: { label: '中断' },
      events: { tap: 'interrupt:' + sid },
      style: {
        flex: 1,
        height: 42,
        backgroundColor: '#EF4444',
        color: '#FFFFFF',
        borderRadius: 21
      }
    });
  } else {
    actionRow.push({
      id: 'btn_refresh_' + sid,
      type: 'button',
      props: { label: '刷新' },
      events: { tap: 'refresh_session:' + sid },
      style: {
        flex: 1,
        height: 42,
        backgroundColor: '#3B82F6',
        color: '#FFFFFF',
        borderRadius: 21
      }
    });
  }

  children.push({
    id: 'ctrl_row_' + sid,
    type: 'row',
    style: {
      width: '100%',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 12
    },
    children: actionRow
  });

  return {
    title: rawTitle,
    header: header,
    refreshMs: isRunning ? 3000 : 0,
    fab: null,
    root: {
      type: 'scroll',
      props: {
        title: rawTitle,
        header: header
      },
      style: { padding: 18, backgroundColor: '#000000' },
      media: {
        circle: { paddingLeft: 42, paddingRight: 42, paddingTop: 16, paddingBottom: 72 },
        pill: { padding: 14 }
      },
      children: children
    }
  };
}

module.exports = {
  manifest: function () {
    return {
      package: { id: PKG, name: 'OpenCode', version: '1.0.1' },
      apps: [{ id: 'main', name: 'OpenCode', icon: 'assets/icon.png' }]
    };
  },

  async renderSessionDetail(cfg, sessionId, mode, scrollToMode, actionErr) {
    var results = await Promise.all([
      ocRequest(cfg, '/api/session/' + encodeURIComponent(sessionId)),
      loadSessionMessages(cfg, sessionId, mode || 'refresh'),
      fetchActiveMap(cfg),
      fetchPermissions(cfg, sessionId)
    ]);
    var rawSession = unwrapData(results[0]);
    var session = Object.assign({ id: sessionId, title: '会话', agent: 'build' }, rawSession);
    if (!session.id) session.id = sessionId;
    var msgState = results[1];
    var activeMap = results[2] || {};
    var perms = results[3] || [];
    var isRunning = !!(activeMap[sessionId] && activeMap[sessionId].type === 'running');
    if (msgState) {
      msgState.session = session;
      msgState.isRunning = isRunning;
      msgState.perms = perms;
    }
    return sessionDetailViewTree(session, msgState, isRunning, perms, actionErr, scrollToMode);
  },

  async renderSessionList(cfg) {
    var results = await Promise.all([
      ocRequest(cfg, '/api/session?limit=15&order=desc'),
      fetchActiveMap(cfg),
      fetchPermissions(cfg, '')
    ]);
    var sessions = unwrapList(results[0]);
    var activeMap = results[1] || {};
    var perms = results[2] || [];

    var anyRunning = Object.keys(activeMap).some(function (k) {
      return activeMap[k] && activeMap[k].type === 'running';
    });
    var topDot = anyRunning ? DOT_RUNNING : DOT_IDLE;

    var children = [];

    // 待处理的权限请求置顶显示
    if (perms.length) {
      for (var p = 0; p < Math.min(perms.length, 2); p++) {
        children.push(permissionCardNode(perms[p]));
      }
    }

    if (!sessions.length) {
      children.push(text('暂无会话，点击下方 + 新建会话', 21, '#94A3B8', { textAlign: 'center', marginTop: 16 }));
    } else {
      for (var i = 0; i < sessions.length; i++) {
        var s = sessions[i];
        if (!s || !s.id) continue;
        var isRun = !!(activeMap[s.id] && activeMap[s.id].type === 'running');
        var sDot = isRun
          ? DOT_RUNNING
          : (s.outcome === 'failed' ? DOT_OFFLINE : DOT_IDLE);
        var sTitle = safeText(s.title || '新会话').slice(0, 36) || '新会话';
        var sTime = formatTime((s.time && (s.time.updated || s.time.created)) || 0);
        var sDir = s.location && s.location.directory ? shortDir(s.location.directory) : '';
        var sAgent = safeText(s.agent || '');
        var metaParts = [];
        if (sAgent) metaParts.push(sAgent);
        if (sDir) metaParts.push(sDir);
        if (sTime) metaParts.push(sTime);

        var openEv = { tap: 'open_session:' + s.id };
        var cardId = 'sess_' + s.id;

        var cardNodes = [
          {
            type: 'row',
            style: { width: '100%', alignItems: 'center', marginBottom: 6 },
            children: [
              statusDotNode(sDot, 12),
              {
                id: cardId + '_title',
                type: 'text',
                props: { text: sTitle },
                events: openEv,
                style: {
                  flex: 1,
                  fontSize: 24,
                  fontWeight: 'bold',
                  color: '#FFFFFF',
                  lines: 1
                }
              }
            ]
          },
          {
            id: cardId + '_meta',
            type: 'text',
            props: { text: metaParts.join(' · ') || s.id.slice(0, 12) },
            events: openEv,
            style: {
              fontSize: 19,
              color: '#969CAD'
            }
          }
        ];

        children.push({
          id: cardId,
          type: 'column',
          style: {
            padding: 14,
            backgroundColor: isRun ? '#1B2433' : '#1E2028',
            borderWidth: isRun ? 1 : 0,
            borderColor: isRun ? '#38BDF8' : 'transparent',
            borderRadius: 16,
            marginBottom: 12
          },
          events: openEv,
          children: cardNodes
        });
      }
    }

    return listViewTree(children, topDot, 0, true);
  },

  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null;
    try {
      var cfg = await kit.storage.getItem(CONFIG_KEY);
      if (!cfg || !cfg.baseUrl) {
        currentSessionId = '';
        return listViewTree([
          text('尚未连接 OpenCode', 24, '#FFFFFF'),
          text('请在手机或电脑打开 OpenCode 插件界面，粘贴 opencode pair 配对链接或填写地址与密码。', 18, '#B7BCCB'),
          button('已配置，刷新', 'refresh')
        ], DOT_OFFLINE, 0, false);
      }

      var handler = (req && req.event && req.event.handler) || (req && req.handler) || '';
      var value = (req && req.event && req.event.value) !== undefined ? req.event.value : '';

      // 1. 返回会话列表
      if (handler === 'back_to_list') {
        currentSessionId = '';
        return await this.renderSessionList(cfg);
      }

      // 2. 新建会话并直接进入（默认定位最底部）
      if (handler === 'new_session') {
        var createBody = {};
        if (cfg.directory && String(cfg.directory).trim()) {
          createBody.location = { directory: String(cfg.directory).trim() };
        }
        var createdRes = await ocRequest(cfg, '/api/session', {
          method: 'POST',
          body: createBody
        });
        var created = unwrapData(createdRes);
        if (created && created.id) {
          currentSessionId = created.id;
          return await this.renderSessionDetail(cfg, created.id, 'reset', 'bottom', '');
        }
        return await this.renderSessionList(cfg);
      }

      // 3. 打开指定会话（首次打开默认定位最底部）
      if (handler.startsWith('open_session:')) {
        var openId = handler.slice('open_session:'.length);
        currentSessionId = openId;
        return await this.renderSessionDetail(cfg, openId, 'reset', 'bottom', '');
      }

      // 4. 向上懒加载更早消息（保持当前阅读滚动偏移）
      if (handler.startsWith('load_older:')) {
        var olderSid = handler.slice('load_older:'.length) || currentSessionId;
        currentSessionId = olderSid;
        return await this.renderSessionDetail(cfg, olderSid, 'older', 'preserve', '');
      }

      // 5. 发送 Prompt 指令（发送后定位到最底部）
      if (handler.startsWith('send_prompt:')) {
        var promptSid = handler.slice('send_prompt:'.length) || currentSessionId;
        currentSessionId = promptSid;
        var promptText = safeText(value);
        var promptErr = '';
        if (promptText) {
          try {
            await ocRequest(cfg, '/api/session/' + encodeURIComponent(promptSid) + '/prompt', {
              method: 'POST',
              body: { text: promptText },
              timeout: 12000
            });
          } catch (e) {
            promptErr = safeText(e && e.message || '发送失败');
          }
        }
        return await this.renderSessionDetail(cfg, promptSid, 'refresh', 'bottom', promptErr);
      }

      // 6. 中断正在运行的会话
      if (handler.startsWith('interrupt:')) {
        var intSid = handler.slice('interrupt:'.length) || currentSessionId;
        currentSessionId = intSid;
        var intErr = '';
        try {
          await ocRequest(cfg, '/api/session/' + encodeURIComponent(intSid) + '/interrupt', {
            method: 'POST',
            body: {}
          });
        } catch (e) {
          intErr = safeText(e && e.message || '中断失败');
        }
        return await this.renderSessionDetail(cfg, intSid, 'refresh', '', intErr);
      }

      // 7. 切换主 Agent
      if (handler.startsWith('cycle_agent:')) {
        var agParts = handler.slice('cycle_agent:'.length).split(':');
        var agSid = agParts[0] || currentSessionId;
        var curAg = agParts[1] || 'build';
        currentSessionId = agSid;
        var agErr = '';
        try {
          var agents = await fetchPrimaryAgents(cfg);
          var idx = agents.indexOf(curAg);
          var nextAg = agents[(idx + 1) % agents.length] || 'build';
          await ocRequest(cfg, '/api/session/' + encodeURIComponent(agSid) + '/agent', {
            method: 'POST',
            body: { agent: nextAg }
          });
        } catch (e) {
          agErr = safeText(e && e.message || '切换 Agent 失败');
        }
        return await this.renderSessionDetail(cfg, agSid, 'refresh', '', agErr);
      }

      // 8. 权限审批回复
      if (handler.startsWith('perm_reply:')) {
        var pParts = handler.slice('perm_reply:'.length).split(':');
        var pSid = pParts[0];
        var pId = pParts[1];
        var decision = pParts[2] || 'once';
        var pErr = '';
        try {
          await ocRequest(
            cfg,
            '/api/session/' + encodeURIComponent(pSid) + '/permission/' + encodeURIComponent(pId) + '/reply',
            {
              method: 'POST',
              body: { decision: decision }
            }
          );
        } catch (e) {
          pErr = safeText(e && e.message || '审批失败');
        }
        if (currentSessionId) {
          return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', '', pErr);
        }
        return await this.renderSessionList(cfg);
      }

      // 9. 展开/折叠同类工具调用详情
      if (handler.startsWith('toggle_tool:')) {
        var tgRest = handler.slice('toggle_tool:'.length);
        var tgSep = tgRest.indexOf(':');
        var tgSid = tgSep >= 0 ? tgRest.slice(0, tgSep) : currentSessionId;
        var tgId = tgSep >= 0 ? tgRest.slice(tgSep + 1) : tgRest;
        if (tgId) {
          expandedToolGroups[tgId] = !expandedToolGroups[tgId];
        }
        currentSessionId = tgSid || currentSessionId;
        var cachedTg = sessionMsgCache[currentSessionId];
        if (cachedTg && cachedTg.session) {
          return sessionDetailViewTree(cachedTg.session, cachedTg, !!cachedTg.isRunning, cachedTg.perms || [], '');
        }
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', 'preserve', '');
      }

      // 10. 展开/折叠消息的时间与模式头部
      if (handler.startsWith('toggle_meta:')) {
        var tmRest = handler.slice('toggle_meta:'.length);
        var tmSep = tmRest.indexOf(':');
        var tmSid = tmSep >= 0 ? tmRest.slice(0, tmSep) : currentSessionId;
        var tmId = tmSep >= 0 ? tmRest.slice(tmSep + 1) : tmRest;
        if (tmId) {
          expandedMetaTurns[tmId] = !expandedMetaTurns[tmId];
        }
        currentSessionId = tmSid || currentSessionId;
        var cachedTm = sessionMsgCache[currentSessionId];
        if (cachedTm && cachedTm.session) {
          return sessionDetailViewTree(cachedTm.session, cachedTm, !!cachedTm.isRunning, cachedTm.perms || [], '');
        }
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', 'preserve', '');
      }

      // 11. 会话内手动刷新或自动轮询刷新
      if (handler.startsWith('refresh_session:')) {
        var refSid = handler.slice('refresh_session:'.length) || currentSessionId;
        currentSessionId = refSid;
        return await this.renderSessionDetail(cfg, refSid, 'refresh', 'bottom', '');
      }

      if (currentSessionId && req && req.trigger === 'refresh') {
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', '', '');
      }

      // 默认进入会话列表首页
      currentSessionId = '';
      return await this.renderSessionList(cfg);
    } catch (error) {
      var errMsg = safeText(error && error.message || '请检查 OpenCode 服务是否运行');
      if (currentSessionId) {
        var failDetail = listViewTree([
          text('会话加载失败', 24, '#FCA5A5'),
          text(errMsg, 18, '#B7BCCB'),
          button('重试', 'open_session:' + currentSessionId),
          button('返回列表', 'back_to_list', '#262936')
        ], DOT_OFFLINE, 0, false);
        failDetail.header = { title: '‹会话', tap: 'back_to_list', dotColor: DOT_OFFLINE };
        if (failDetail.root && failDetail.root.props) {
          failDetail.root.props.header = failDetail.header;
        }
        return failDetail;
      }
      return listViewTree([
        text('无法连接 OpenCode', 24, '#FCA5A5'),
        text(errMsg, 18, '#B7BCCB'),
        button('重试', 'refresh')
      ], DOT_OFFLINE, 0, false);
    }
  }
};

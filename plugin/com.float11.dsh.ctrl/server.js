// HydroPlugin: DeepSeek Harness 控制器 (com.float11.dsh.ctrl)
// 对接 DeepSeek Harness (https://deepseek-harness.github.io/deepseek-harness/)
// 1. 严禁包含 Emoji（Vela 系统不支持显示 Emoji，全局 safeText 过滤）
// 2. 顶栏与 HydroBili / OpenCode 对齐，状态以单个圆点显示
// 3. 会话内消息按时间正序排列，默认定位最底部，支持触顶懒加载更早消息
// 4. 支持工具权限审批与交互提问置顶展示与手表直接应答

var PKG = 'com.float11.dsh.ctrl';
var CONFIG_KEY = 'dsh_config';
var PAGE_SIZE = 20;

var DOT_IDLE = '#4ADE80';    // 在线 / 空闲（绿）
var DOT_RUNNING = '#38BDF8'; // 运行中（亮蓝）
var DOT_OFFLINE = '#F87171'; // 未连接 / 异常（红）

// 内存状态：当前打开的会话 ID、消息缓存、模型目录缓存、折叠状态
var currentSessionId = '';
var sessionMsgCache = {}; // sessionId -> { list: [], cursor: null, hasMore: false, session: null, isRunning: false, approvals: [], questions: [] }
var modelCatalogCache = null;
var expandedToolGroups = {}; // groupId -> boolean
var expandedMetaTurns = {};  // turnId -> boolean
var lastRunningState = {};   // sessionId -> boolean

function safeText(value) {
  return String(value == null ? '' : value)
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, ' ')
    .trim();
}

function formatTime(ts) {
  if (!ts) return '';
  var d = new Date(ts);
  if (isNaN(d.getTime())) return '';
  var h = ('0' + d.getHours()).slice(-2);
  var m = ('0' + d.getMinutes()).slice(-2);
  return h + ':' + m;
}

function shortDir(p) {
  if (!p || typeof p !== 'string') return '';
  var clean = p.replace(/[\\/]+$/, '');
  var parts = clean.split(/[\\/]/).filter(Boolean);
  if (parts.length <= 1) return safeText(clean);
  return safeText(parts.slice(-2).join('/'));
}

function shortPath(p) {
  if (!p || typeof p !== 'string') return '';
  var clean = p.replace(/[\\/]+$/, '');
  var parts = clean.split(/[\\/]/).filter(Boolean);
  if (parts.length <= 2) return safeText(clean);
  return safeText(parts.slice(-2).join('/'));
}

function compactOneLine(raw, maxLen) {
  var limit = maxLen || 46;
  var s = safeText(String(raw == null ? '' : raw).replace(/\s+/g, ' '));
  if (!s) return '';
  if (s.length > limit) return s.slice(0, limit) + '…';
  return s;
}

// ---------------------------------------------------------------- 网络与请求封装
// 统一请求封装：优先通过同源 /api/hydro/proxy 发起以规避 WebView CORS 与受限请求头
async function dshFetch(targetUrl, options) {
  var opts = Object.assign({}, options || {});
  if (typeof location !== 'undefined' && location.origin && /^https?:\/\//i.test(location.origin)) {
    var proxyUrl = location.origin.replace(/\/$/, '') + '/api/hydro/proxy?url=' + encodeURIComponent(targetUrl);
    return await fetch(proxyUrl, opts);
  }
  return await fetch(targetUrl, opts);
}

// 统一 DSH 请求调度（支持 DSH 原生 RPC 与 Companion Bridge REST 接口）
async function dshRequest(cfg, endpoint, argsOrBody, methodOverride) {
  var base = (cfg.baseUrl || 'http://127.0.0.1:3080').replace(/\/+$/, '');
  var isBridge = endpoint.startsWith('/hydro-dsh') || endpoint.startsWith('hydro-dsh');
  var targetUrl = isBridge
    ? (base + (endpoint.startsWith('/') ? endpoint : ('/' + endpoint)))
    : (base + '/api/' + endpoint.replace(/^\/+/, ''));

  var headers = {
    'Accept': 'application/json',
    'Content-Type': 'application/json; charset=utf-8',
    'Origin': base,
    'X-Proxy-Origin': base
  };

  if (cfg.cookie) {
    headers['Cookie'] = cfg.cookie;
    headers['X-Proxy-Cookie'] = cfg.cookie;
  }
  if (cfg.token) {
    headers['Authorization'] = 'Bearer ' + cfg.token;
    headers['X-Proxy-Authorization'] = 'Bearer ' + cfg.token;
  }

  var isGet = methodOverride === 'GET' || (isBridge && !argsOrBody);
  var fetchOpts = {
    method: isGet ? 'GET' : 'POST',
    headers: headers
  };

  if (!isGet) {
    if (isBridge) {
      fetchOpts.body = JSON.stringify(argsOrBody || {});
    } else {
      // DSH Native RPC client-request envelope
      var rpcId = 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
      var method = endpoint.replace(/^\/+/, '');
      var args = {};

      if (method === 'session/list') {
        args = { _request: (argsOrBody && typeof argsOrBody === 'object' && ('_request' in argsOrBody)) ? argsOrBody._request : (argsOrBody || {}) };
      } else if (method === 'session/modelCatalog' || method === 'session/canOpenWorkspacePath' || method === 'session/initializeDefaultModel') {
        args = {};
      } else {
        // DSH session RPC methods (session/cancel, session/create, session/page, session/projections, session/prompt, session/selectModel, etc.)
        args = { request: (argsOrBody && typeof argsOrBody === 'object' && ('request' in argsOrBody)) ? argsOrBody.request : (argsOrBody || {}) };
      }

      fetchOpts.body = JSON.stringify({
        type: 'client-request',
        rpcId: rpcId,
        method: method,
        payload: {
          args: args
        }
      });
    }
  }

  var controller = null;
  var timer = null;
  var timeoutMs = 15000;
  if (typeof AbortController !== 'undefined') {
    controller = new AbortController();
    fetchOpts.signal = controller.signal;
  }

  try {
    var op = (async function () {
      var res = await dshFetch(targetUrl, fetchOpts);
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
        var errMsg = (data && (data.message || data.error || (data.result && data.result.error && data.result.error.message))) ||
          ('HTTP ' + res.status);
        throw new Error(safeText(errMsg));
      }
      // DSH Native RPC 响应解包
      if (!isBridge && data && data.type === 'server-response') {
        if (data.result && data.result.ok === false) {
          var err = data.result.error || {};
          throw new Error(safeText(err.message || err.code || 'RPC 调用失败'));
        }
        return data.result ? data.result.value : null;
      }
      return data;
    })();

    return await Promise.race([
      op,
      new Promise(function (_, reject) {
        timer = setTimeout(function () {
          if (controller) controller.abort();
          reject(new Error('连接 DeepSeek Harness 超时'));
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

// ---------------------------------------------------------------- 数据拉取辅助函数
async function fetchBridgePending(cfg, sessionId) {
  try {
    var path = '/hydro-dsh/pending' + (sessionId ? ('?sessionId=' + encodeURIComponent(sessionId)) : '');
    var res = await dshRequest(cfg, path, null, 'GET');
    if (res && res.status === 'ok') {
      return {
        approvals: res.approvals || [],
        questions: res.questions || []
      };
    }
  } catch (e) {}
  return { approvals: [], questions: [] };
}

async function fetchSessionState(cfg, sessionId) {
  try {
    var res = await dshRequest(cfg, 'session/projections', { sessionId: sessionId });
    if (!res) return {};
    var vals = res.values || {};
    var inbox = vals.inbox || {};
    var nextStep = Array.isArray(inbox['next-step']) ? inbox['next-step'] : [];
    var nextTurn = Array.isArray(inbox['next-turn']) ? inbox['next-turn'] : [];
    var isRunning = nextStep.length > 0 || nextTurn.length > 0;
    var ms = vals.modelSelection || {};
    var activeModel = ms.next || ms.lastUsed || null;
    return {
      asOfSeq: typeof res.asOfSeq === 'number' ? res.asOfSeq : 0,
      title: vals.title || (vals.sessionTitle && vals.sessionTitle.title) || vals.goal || (vals.workingDirectory ? shortDir(vals.workingDirectory) : '') || null,
      workingDirectory: vals.workingDirectory || null,
      modelSelection: activeModel,
      running: isRunning,
      raw: res
    };
  } catch (e) {
    try {
      var alt = await dshRequest(cfg, 'session/state', { sessionId: sessionId });
      return alt || {};
    } catch (e2) {}
  }
  return {};
}

async function fetchModelCatalog(cfg) {
  if (modelCatalogCache && (Date.now() - modelCatalogCache.time < 180000)) {
    return modelCatalogCache.models;
  }
  try {
    var res = await dshRequest(cfg, 'session/modelCatalog', {});
    var list = [];
    if (res && Array.isArray(res.groups)) {
      var routable = Array.isArray(res.routableProviders) && res.routableProviders.length ? res.routableProviders : null;
      for (var g = 0; g < res.groups.length; g++) {
        var group = res.groups[g];
        var pid = group.id || 'deepseek-official';
        if (routable && routable.indexOf(pid) < 0) continue;
        var models = group.models || [];
        for (var m = 0; m < models.length; m++) {
          var mo = models[m];
          var mid = mo.id || '';
          if (!mid) continue;
          var mName = safeText(mo.name || mid);
          var defEffort = (mo.reasoning && mo.reasoning.defaultEffort) || 'high';
          list.push({
            provider: pid,
            model: mid,
            name: mName,
            groupName: safeText(group.name || pid),
            defaultEffort: defEffort
          });
        }
      }
    }
    if (list.length) {
      modelCatalogCache = { models: list, time: Date.now() };
      return list;
    }
  } catch (e) {}
  return [
    { provider: 'deepseek-official', model: 'deepseek-flash', name: 'DeepSeek-V4.1-Flash', defaultEffort: 'high' },
    { provider: 'deepseek-official', model: 'deepseek-v4-pro', name: 'DeepSeek-V4-Pro', defaultEffort: 'high' }
  ];
}

// ---------------------------------------------------------------- 消息解析与归并
function extractToolDetail(toolName, inp) {
  if (!inp) return '';
  var name = String(toolName || '').toLowerCase();
  var raw = '';

  if (typeof inp === 'string') {
    raw = inp;
  } else if (typeof inp === 'object') {
    if (name === 'bash' || name === 'shell' || name === 'exec') {
      raw = inp.command || inp.cmd || inp.description || '';
    } else if (name === 'read' || name === 'write' || name === 'edit') {
      var sp = shortPath(inp.path || inp.filePath || inp.file || '');
      raw = sp + (inp.offset ? (':' + inp.offset) : '');
    } else if (name === 'grep') {
      raw = (inp.pattern || inp.query || '') + (inp.path ? (' (' + shortPath(inp.path) + ')') : '');
    } else if (name === 'glob') {
      raw = inp.pattern || shortPath(inp.path || '') || '';
    } else {
      raw = inp.command || inp.query || inp.url || inp.path || inp.description || '';
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
  return compactOneLine(raw, 48);
}

// 从 DSH 的 SessionWireEvent records 转换为统一 message 格式
function normalizeDshRecords(records) {
  var list = [];
  if (!Array.isArray(records)) return list;

  for (var i = 0; i < records.length; i++) {
    var rec = records[i];
    var evt = (rec && rec.event) ? rec.event : rec;
    if (!evt || !evt.type) continue;

    var type = evt.type;
    var time = evt.time || 0;
    var data = evt.data || {};

    if (type === 'user/message') {
      var uText = '';
      if (Array.isArray(data.content)) {
        uText = data.content.map(function (c) { return safeText(c && c.text || ''); }).filter(Boolean).join('\n');
      } else if (typeof data.text === 'string') {
        uText = safeText(data.text);
      }
      list.push({
        id: 'usr_' + (evt.seq || i),
        role: 'user',
        time: time,
        text: uText || '[空输入]'
      });
    } else if (type === 'assistant/message' || type === 'assistant/attempt') {
      var aParts = [];
      var hasReasoning = false;
      if (Array.isArray(data.content)) {
        for (var cIdx = 0; cIdx < data.content.length; cIdx++) {
          var item = data.content[cIdx];
          if (!item) continue;
          if (item.type === 'text' && item.text) {
            aParts.push({ kind: 'text', text: safeText(item.text) });
          } else if (item.type === 'reasoning' || item.type === 'thought') {
            hasReasoning = true;
          } else if (item.type === 'tool' || item.type === 'tool_call') {
            aParts.push({
              kind: 'tool',
              id: item.id || ('tool_' + cIdx),
              name: safeText(item.name || item.tool || 'tool'),
              status: 'completed',
              detail: extractToolDetail(item.name, item.input || item.args)
            });
          }
        }
      } else if (typeof data.text === 'string') {
        aParts.push({ kind: 'text', text: safeText(data.text) });
      }

      list.push({
        id: 'asst_' + (evt.seq || i),
        role: 'assistant',
        time: time,
        agent: 'DeepSeek',
        orderedParts: aParts,
        hasReasoning: hasReasoning
      });
    } else if (type === 'tool/call') {
      var tName = safeText(data.name || data.toolName || 'tool');
      var callId = data.id || data.callId || ('call_' + i);
      var detail = extractToolDetail(tName, data.input || data.args);
      list.push({
        id: 'tc_' + (evt.seq || i),
        role: 'assistant',
        time: time,
        orderedParts: [{
          kind: 'tool',
          id: callId,
          name: tName,
          status: 'running',
          detail: detail
        }]
      });
    } else if (type === 'tool/result') {
      var resCallId = data.callId || data.id;
      // 找到前置 tool/call 并标记为完成或错误
      for (var rIdx = list.length - 1; rIdx >= 0; rIdx--) {
        var m = list[rIdx];
        if (m.orderedParts) {
          for (var p = 0; p < m.orderedParts.length; p++) {
            if (m.orderedParts[p].id === resCallId) {
              m.orderedParts[p].status = data.error ? 'error' : 'completed';
              break;
            }
          }
        }
      }
    }
  }
  return list;
}

function mergeToolStatus(items) {
  var hasRunning = false;
  var hasError = false;
  for (var i = 0; i < items.length; i++) {
    var st = items[i].status;
    if (st === 'running' || st === 'pending') hasRunning = true;
    if (st === 'error') hasError = true;
  }
  if (hasRunning) return 'running';
  if (hasError) return 'error';
  return 'completed';
}

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

function buildConversationTurns(list) {
  var rawTurns = [];
  for (var i = 0; i < list.length; i++) {
    var m = list[i];
    var isUser = m.role === 'user';
    var prev = rawTurns.length ? rawTurns[rawTurns.length - 1] : null;

    if (isUser) {
      rawTurns.push({
        id: m.id || ('turn_' + i),
        role: 'user',
        time: m.time ? formatTime(m.time) : '',
        orderedParts: [{ kind: 'text', text: m.text || '' }],
        hasReasoning: false
      });
    } else {
      var parts = m.orderedParts || [];
      if (prev && prev.role === 'assistant') {
        for (var p = 0; p < parts.length; p++) {
          prev.orderedParts.push(parts[p]);
        }
        if (m.hasReasoning) prev.hasReasoning = true;
        if (m.time) prev.time = formatTime(m.time);
      } else {
        rawTurns.push({
          id: m.id || ('turn_' + i),
          role: 'assistant',
          time: m.time ? formatTime(m.time) : '',
          agent: m.agent || 'DeepSeek',
          orderedParts: parts.slice(),
          hasReasoning: !!m.hasReasoning
        });
      }
    }
  }

  return rawTurns.map(function (turn) {
    var blocks = buildOrderedBlocks(turn.orderedParts, turn.id);
    if (!blocks.length) {
      blocks.push({
        kind: 'text',
        id: turn.id + '_txt_0',
        text: turn.hasReasoning ? '思考中…' : '正在生成…'
      });
    }
    var timeLabel = turn.time ? (' · ' + turn.time) : '';
    var metaHeader = turn.role === 'user' ? ('你' + timeLabel) : ((turn.agent || 'DeepSeek') + timeLabel);

    return {
      id: turn.id,
      role: turn.role,
      metaHeader: metaHeader,
      blocks: blocks
    };
  });
}

// ---------------------------------------------------------------- 历史消息分页拉取
async function loadSessionHistory(cfg, sessionId, mode) {
  var state = sessionMsgCache[sessionId];
  if (!state || mode === 'reset') {
    state = { list: [], throughSeq: null, minSeq: null, hasMore: false };
    sessionMsgCache[sessionId] = state;
  }

  try {
    var throughSeq = state.throughSeq;
    if (mode === 'reset' || throughSeq === null || throughSeq === undefined) {
      var proj = await fetchSessionState(cfg, sessionId);
      throughSeq = typeof proj.asOfSeq === 'number' ? proj.asOfSeq : 0;
      state.throughSeq = throughSeq;
    }

    if (throughSeq < 0 && mode === 'older') {
      state.hasMore = false;
      return state;
    }

    var pageReq = {
      address: { kind: 'session', sessionId: sessionId },
      throughSeq: throughSeq,
      maxMessages: PAGE_SIZE
    };
    if (mode === 'older' && state.minSeq !== null && state.minSeq !== undefined) {
      pageReq.beforeSeq = state.minSeq;
    }

    var page = null;
    try {
      page = await dshRequest(cfg, 'session/page', pageReq);
    } catch (pageErr) {
      page = await dshRequest(cfg, 'session/events', {
        address: { kind: 'session', sessionId: sessionId },
        maxMessages: PAGE_SIZE,
        throughSeq: state.minSeq != null ? state.minSeq : undefined
      });
    }

    if (page && Array.isArray(page.records)) {
      var norm = normalizeDshRecords(page.records);
      var recMinSeq = null;
      for (var r = 0; r < page.records.length; r++) {
        var recEvt = page.records[r] && (page.records[r].event || page.records[r]);
        if (recEvt && typeof recEvt.seq === 'number') {
          if (recMinSeq === null || recEvt.seq < recMinSeq) {
            recMinSeq = recEvt.seq;
          }
        }
      }
      if (mode === 'older') {
        state.list = norm.concat(state.list);
      } else {
        state.list = norm;
      }
      if (recMinSeq !== null) {
        state.minSeq = (state.minSeq === null || recMinSeq < state.minSeq) ? recMinSeq : state.minSeq;
      }
      state.hasMore = !!page.hasMore;
    }
  } catch (e) {
    if (!state.list.length) throw e;
  }

  return state;
}

// ---------------------------------------------------------------- UI 节点构建辅助
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
      backgroundColor: bg || '#1D63FF',
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

// 待处理审批卡片
function approvalCardNode(sid, appr) {
  var aid = appr.id || '';
  var toolName = safeText(appr.toolName || '工具权限请求');
  var detail = safeText(appr.reason || appr.callId || '需要确认操作').slice(0, 90);

  return {
    id: 'card_appr_' + aid,
    type: 'column',
    style: {
      padding: 12,
      backgroundColor: '#2A1D1A',
      borderRadius: 16,
      marginBottom: 12,
      borderWidth: 1,
      borderColor: '#F59E0B'
    },
    children: [
      {
        type: 'text',
        props: { text: '审批: ' + toolName },
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
            props: { label: '允许一次' },
            events: { tap: 'appr_reply:' + sid + ':' + aid + ':allowed-once' },
            style: { flex: 1, height: 40, backgroundColor: '#10B981', color: '#FFFFFF', borderRadius: 20, marginRight: 8 }
          },
          {
            type: 'button',
            props: { label: '拒绝' },
            events: { tap: 'appr_reply:' + sid + ':' + aid + ':rejected' },
            style: { flex: 1, height: 40, backgroundColor: '#EF4444', color: '#FFFFFF', borderRadius: 20 }
          }
        ]
      }
    ]
  };
}

// 待回答提问卡片
function questionCardNode(sid, ques) {
  var qid = ques.id || '';
  var items = Array.isArray(ques.questions) ? ques.questions : [];
  var firstQ = items[0] || {};
  var qTitle = safeText(firstQ.question || firstQ.header || '请选择或回答');
  var qDetail = safeText(firstQ.detail || '').slice(0, 80);
  var options = Array.isArray(firstQ.options) ? firstQ.options : [];

  var optButtons = [];
  for (var optIdx = 0; optIdx < Math.min(options.length, 3); optIdx++) {
    var opt = options[optIdx];
    var lbl = safeText(opt && (opt.label || opt) || ('选项 ' + (optIdx + 1)));
    optButtons.push({
      type: 'button',
      props: { label: lbl },
      events: { tap: 'ques_reply:' + sid + ':' + qid + ':' + encodeURIComponent(lbl) },
      style: {
        width: '100%',
        height: 38,
        backgroundColor: '#1E293B',
        color: '#38BDF8',
        borderRadius: 19,
        marginBottom: 6
      }
    });
  }

  var children = [
    {
      type: 'text',
      props: { text: '交互提问: ' + qTitle },
      style: { fontSize: 19, fontWeight: 'bold', color: '#38BDF8', marginBottom: 4 }
    }
  ];
  if (qDetail) {
    children.push({
      type: 'text',
      props: { text: qDetail },
      style: { fontSize: 15, color: '#CBD5E1', marginBottom: 8 }
    });
  }
  children = children.concat(optButtons);

  return {
    id: 'card_ques_' + qid,
    type: 'column',
    style: {
      padding: 12,
      backgroundColor: '#132338',
      borderRadius: 16,
      marginBottom: 12,
      borderWidth: 1,
      borderColor: '#38BDF8'
    },
    children: children
  };
}

// ---------------------------------------------------------------- 视图树渲染
function listViewTree(children, dotColor, refreshMs, showFab) {
  var title = 'DeepSeek Harness';
  var fabConfig = showFab === false ? null : {
    icon: '/common/icons/plus.png',
    tap: 'new_session',
    background: '#1D63FF'
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

function sessionDetailViewTree(session, msgState, isRunning, approvals, questions, errText) {
  var sid = session.id;
  var rawTitle = safeText(session.title || '新会话').slice(0, 14) || '会话';
  var header = {
    title: '‹' + rawTitle,
    tap: 'back_to_list'
  };

  var children = [];

  // 1. 顶部加载更早消息
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

  // 2. 消息流
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

      var blocks = turn.blocks || [];
      for (var bIdx = 0; bIdx < blocks.length; bIdx++) {
        var blk = blocks[bIdx];
        var isLastBlock = (bIdx === blocks.length - 1);

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
            : (g.status === 'error' ? ' · 失败' : ' · 完成');
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
                style: { fontSize: 18, color: stColor, marginBottom: 4 }
              });
              for (var k = 0; k < g.items.length; k++) {
                var item = g.items[k];
                cardChildren.push({
                  id: 'tg_' + g.id + '_it_' + k,
                  type: 'text',
                  props: { text: '  ' + (k + 1) + '. ' + (item.detail || item.name) },
                  events: tgTapEv,
                  style: {
                    fontSize: 17,
                    color: '#CBD5E1',
                    marginBottom: (k === g.items.length - 1 && isLastBlock) ? 0 : 3
                  }
                });
              }
            }
          }
        }
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

  // 3. 待处理权限审批 / 交互提问
  if (Array.isArray(approvals) && approvals.length) {
    for (var a = 0; a < approvals.length; a++) {
      children.push(approvalCardNode(sid, approvals[a]));
    }
  }
  if (Array.isArray(questions) && questions.length) {
    for (var q = 0; q < questions.length; q++) {
      children.push(questionCardNode(sid, questions[q]));
    }
  }

  if (errText) {
    children.push(text(errText, 17, '#FCA5A5', { marginBottom: 8 }));
  }

  // 4. 输入框与底部操作栏
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

  var curModelId = safeText(session.model || 'deepseek-flash');
  var displayName = safeText(session.modelName || curModelId);
  var shortModelName = displayName.replace(/^DeepSeek[-_]?/i, '');
  if (shortModelName.length > 12) shortModelName = shortModelName.slice(0, 11) + '…';
  var actionRow = [
    {
      id: 'btn_model_' + sid,
      type: 'button',
      props: { label: '模型: ' + (shortModelName || curModelId) },
      events: { tap: 'cycle_model:' + sid + ':' + curModelId },
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
        backgroundColor: '#1D63FF',
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
      package: { id: PKG, name: 'DeepSeek Harness', version: '1.0.1' },
      apps: [{ id: 'main', name: 'DeepSeek Harness', icon: 'assets/icon.png' }]
    };
  },

  async renderSessionDetail(cfg, sessionId, mode, actionErr) {
    var results = await Promise.all([
      loadSessionHistory(cfg, sessionId, mode || 'refresh'),
      fetchSessionState(cfg, sessionId),
      fetchBridgePending(cfg, sessionId),
      fetchModelCatalog(cfg)
    ]);

    var msgState = results[0];
    var state = results[1] || {};
    var pending = results[2] || { approvals: [], questions: [] };
    var catalog = results[3] || [];

    var ms = state.modelSelection || {};
    var currentMoId = ms.model || cfg.defaultModel || (catalog[0] && catalog[0].model) || 'deepseek-flash';
    var matchedMo = catalog.find(function (c) { return c.model === currentMoId; }) || null;
    var modelName = (matchedMo && matchedMo.name) || currentMoId;
    var currentProvider = (matchedMo && matchedMo.provider) || ms.provider || 'deepseek-official';
    var currentEffort = ms.reasoningEffort || (matchedMo && matchedMo.defaultEffort) || 'high';

    var isRunning = !!state.running;
    var session = {
      id: sessionId,
      title: state.title || '会话',
      model: currentMoId,
      modelName: modelName,
      provider: currentProvider,
      reasoningEffort: currentEffort
    };

    // 检查完成提醒
    if (lastRunningState[sessionId] === true && !isRunning && cfg.notifyOnComplete) {
      try {
        await kit.notify({
          title: 'DeepSeek Harness',
          body: '会话 ' + (session.title || sessionId.slice(0, 8)) + ' 已完成响应'
        });
      } catch (e) {}
    }
    lastRunningState[sessionId] = isRunning;

    if (msgState) {
      msgState.session = session;
      msgState.isRunning = isRunning;
      msgState.approvals = pending.approvals;
      msgState.questions = pending.questions;
    }

    return sessionDetailViewTree(session, msgState, isRunning, pending.approvals, pending.questions, actionErr);
  },

  async renderSessionList(cfg) {
    var results = await Promise.all([
      dshRequest(cfg, 'session/list', {}),
      fetchBridgePending(cfg, '')
    ]);

    var listVal = results[0] || {};
    var allSessions = Array.isArray(listVal.items) ? listVal.items : [];
    var rawSessions = allSessions.slice(0, 8);
    var pending = results[1] || { approvals: [], questions: [] };

    var anyRunning = allSessions.some(function (s) { return !!s.running; });
    var topDot = anyRunning ? DOT_RUNNING : DOT_IDLE;

    var children = [];

    // 待处理审批置顶
    if (pending.approvals && pending.approvals.length) {
      for (var a = 0; a < Math.min(pending.approvals.length, 2); a++) {
        children.push(approvalCardNode(pending.approvals[a].sessionId || '', pending.approvals[a]));
      }
    }
    if (pending.questions && pending.questions.length) {
      for (var q = 0; q < Math.min(pending.questions.length, 2); q++) {
        children.push(questionCardNode(pending.questions[q].sessionId || '', pending.questions[q]));
      }
    }

    if (!rawSessions.length) {
      children.push(text('暂无会话，点击下方 + 新建会话', 21, '#94A3B8', { textAlign: 'center', marginTop: 16 }));
    } else {
      for (var i = 0; i < rawSessions.length; i++) {
        var s = rawSessions[i];
        if (!s || !s.sessionId) continue;
        var sid = s.sessionId;
        var isRun = !!s.running;
        var sDot = isRun ? DOT_RUNNING : DOT_IDLE;
        var sTitle = safeText(s.title || (s.projections && s.projections.values && s.projections.values.title) || (s.projections && s.projections.values && s.projections.values.goal) || (s.cwd ? shortDir(s.cwd) : '') || '会话').slice(0, 24) || sid.slice(0, 10);
        var sTime = formatTime(s.updatedAt || 0);
        var sDir = s.cwd ? shortDir(s.cwd) : '';

        var metaParts = [];
        if (sDir) metaParts.push(sDir);
        if (sTime) metaParts.push(sTime);

        var openEv = { tap: 'open_session:' + sid };
        var cardId = 'sess_' + sid;

        children.push({
          id: cardId,
          type: 'column',
          style: {
            padding: 14,
            backgroundColor: isRun ? '#141E33' : '#1A1D27',
            borderWidth: isRun ? 1 : 0,
            borderColor: isRun ? '#1D63FF' : 'transparent',
            borderRadius: 16,
            marginBottom: 12
          },
          events: openEv,
          children: [
            {
              type: 'row',
              style: { width: '100%', alignItems: 'center', marginBottom: 6 },
              children: [
                statusDotNode(sDot, 12),
                {
                  id: cardId + '_title',
                  type: 'text',
                  props: { text: sTitle },
                  style: { flex: 1, fontSize: 24, fontWeight: 'bold', color: '#FFFFFF', lines: 1 }
                }
              ]
            },
            {
              id: cardId + '_meta',
              type: 'text',
              props: { text: metaParts.join(' · ') || sid.slice(0, 10) },
              style: { fontSize: 19, color: '#969CAD' }
            }
          ]
        });
      }

      if (allSessions.length > rawSessions.length) {
        children.push(text('仅显示最近 ' + rawSessions.length + ' 个会话 · 更多在电脑查看', 18, '#64748B', { textAlign: 'center', marginTop: 6, marginBottom: 12 }));
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
          text('尚未连接 DeepSeek Harness', 24, '#FFFFFF'),
          text('请在手机或电脑打开本插件界面，填写 DSH 地址（默认 http://127.0.0.1:3080）与凭证。', 18, '#B7BCCB'),
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

      // 2. 新建会话并直接进入
      if (handler === 'new_session') {
        var createPayload = {};
        if (cfg.directory && String(cfg.directory).trim()) {
          createPayload.cwd = String(cfg.directory).trim();
        }
        var created = await dshRequest(cfg, 'session/create', createPayload);
        if (created && created.sessionId) {
          currentSessionId = created.sessionId;
          return await this.renderSessionDetail(cfg, created.sessionId, 'reset', '');
        }
        return await this.renderSessionList(cfg);
      }

      // 3. 打开指定会话
      if (handler.startsWith('open_session:')) {
        var openId = handler.slice('open_session:'.length);
        currentSessionId = openId;
        return await this.renderSessionDetail(cfg, openId, 'reset', '');
      }

      // 4. 向上加载更早消息
      if (handler.startsWith('load_older:')) {
        var olderSid = handler.slice('load_older:'.length) || currentSessionId;
        currentSessionId = olderSid;
        return await this.renderSessionDetail(cfg, olderSid, 'older', '');
      }

      // 5. 发送 Prompt 指令
      if (handler.startsWith('send_prompt:')) {
        var promptSid = handler.slice('send_prompt:'.length) || currentSessionId;
        currentSessionId = promptSid;
        var promptText = safeText(value);
        var promptErr = '';
        if (promptText) {
          try {
            await dshRequest(cfg, 'session/prompt', {
              requestId: 'req_' + Date.now(),
              sessionId: promptSid,
              mode: 'queue',
              content: [{ type: 'text', text: promptText }]
            });
          } catch (e) {
            promptErr = safeText(e && e.message || '发送失败');
          }
        }
        return await this.renderSessionDetail(cfg, promptSid, 'refresh', promptErr);
      }

      // 6. 中断正在执行的任务
      if (handler.startsWith('interrupt:')) {
        var intSid = handler.slice('interrupt:'.length) || currentSessionId;
        currentSessionId = intSid;
        var intErr = '';
        try {
          await dshRequest(cfg, 'session/cancel', { sessionId: intSid });
        } catch (e) {
          intErr = safeText(e && e.message || '中断失败');
        }
        return await this.renderSessionDetail(cfg, intSid, 'refresh', intErr);
      }

      // 7. 切换可用模型
      if (handler.startsWith('cycle_model:')) {
        var moParts = handler.slice('cycle_model:'.length).split(':');
        var moSid = moParts[0] || currentSessionId;
        var curMo = moParts[1] || '';
        currentSessionId = moSid;
        var moErr = '';
        try {
          var catalog = await fetchModelCatalog(cfg);
          if (!catalog || !catalog.length) {
            throw new Error('未获取到可用模型');
          }
          var mIdx = catalog.findIndex(function (c) { return c.model === curMo; });
          var nextItem = catalog[(mIdx + 1) % catalog.length];
          await dshRequest(cfg, 'session/selectModel', {
            sessionId: moSid,
            provider: nextItem.provider,
            model: nextItem.model,
            reasoningEffort: nextItem.defaultEffort || 'high'
          });
        } catch (e) {
          moErr = safeText(e && e.message || '切换模型失败');
        }
        return await this.renderSessionDetail(cfg, moSid, 'refresh', moErr);
      }

      // 8. 审批操作
      if (handler.startsWith('appr_reply:')) {
        var aParts = handler.slice('appr_reply:'.length).split(':');
        var aSid = aParts[0];
        var aId = aParts[1];
        var aOutcome = aParts[2] || 'allowed-once';
        var aErr = '';
        try {
          await dshRequest(cfg, '/hydro-dsh/approval/reply', {
            id: aId,
            outcome: aOutcome
          });
        } catch (e) {
          aErr = safeText(e && e.message || '审批应答失败');
        }
        if (currentSessionId) {
          return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', aErr);
        }
        return await this.renderSessionList(cfg);
      }

      // 9. 提问回答
      if (handler.startsWith('ques_reply:')) {
        var qParts = handler.slice('ques_reply:'.length).split(':');
        var qSid = qParts[0];
        var qId = qParts[1];
        var qAns = decodeURIComponent(qParts[2] || '');
        var qErr = '';
        try {
          await dshRequest(cfg, '/hydro-dsh/question/reply', {
            id: qId,
            answers: [{ id: 'q1', selected: [qAns] }]
          });
        } catch (e) {
          qErr = safeText(e && e.message || '提问应答失败');
        }
        if (currentSessionId) {
          return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', qErr);
        }
        return await this.renderSessionList(cfg);
      }

      // 10. 折叠/展开工具组
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
          return sessionDetailViewTree(cachedTg.session, cachedTg, !!cachedTg.isRunning, cachedTg.approvals || [], cachedTg.questions || [], '');
        }
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', '');
      }

      // 11. 折叠/展开消息元信息
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
          return sessionDetailViewTree(cachedTm.session, cachedTm, !!cachedTm.isRunning, cachedTm.approvals || [], cachedTm.questions || [], '');
        }
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', '');
      }

      // 12. 会话内手动刷新或定时轮询
      if (handler.startsWith('refresh_session:')) {
        var refSid = handler.slice('refresh_session:'.length) || currentSessionId;
        currentSessionId = refSid;
        return await this.renderSessionDetail(cfg, refSid, 'refresh', '');
      }

      if (currentSessionId && req && req.trigger === 'refresh') {
        return await this.renderSessionDetail(cfg, currentSessionId, 'refresh', '');
      }

      // 默认进入列表
      currentSessionId = '';
      return await this.renderSessionList(cfg);
    } catch (error) {
      var errMsg = safeText(error && error.message || '请检查 DeepSeek Harness 服务是否运行');
      if (currentSessionId) {
        var failDetail = listViewTree([
          text('会话加载失败', 24, '#FCA5A5'),
          text(errMsg, 18, '#B7BCCB'),
          button('重试', 'open_session:' + currentSessionId),
          button('返回列表', 'back_to_list', '#262936')
        ], DOT_OFFLINE, 0, false);
        failDetail.header = { title: '‹会话', tap: 'back_to_list', dotColor: DOT_OFFLINE };
        return failDetail;
      }
      return listViewTree([
        text('无法连接 DeepSeek Harness', 24, '#FCA5A5'),
        text(errMsg, 18, '#B7BCCB'),
        button('重试', 'refresh')
      ], DOT_OFFLINE, 0, false);
    }
  }
};

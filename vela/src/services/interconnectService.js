/**
 * interconnectService
 *
 * Wraps @system.interconnect to provide:
 *  - connection status callback
 *  - server auto-discovery (send "discover", expect "server_info")
 *  - API request routing over interconnect
 *
 * Based on the official interconnect_image_demo pattern:
 *  - interconnect.instance() creates a connection; callbacks set immediately after
 *  - getReadyState() called once to check initial status
 *  - onopen fires when connection is established
 *  - send() data param is Object type; fail callback receives (data) where data.code has the error
 *  - onmessage receives data as String in data.data
 *
 * Prerequisites:
 *  - Quick app manifest.json package must match Android companion app applicationId
 *  - Quick app signing (private.pem/certificate.pem) must be derived from Android app's keystore
 *  - Both must be in /sign/debug and /sign/release
 */
import interconnect from '@system.interconnect'

const API_TIMEOUT = 15000
const DISCOVER_TIMEOUT = 10000
// 有请求在途时每隔这么久给手机发一条 keepalive：ColorOS 的 Hans 会在 App 最后一次
// 收到 binder 调用约 3 秒后把它冻结并关掉它的全部 socket（真机日志：
// `freeze uid … DestroyAllSocket`），转发到电脑的慢请求会被拦腰斩断。
const KEEPALIVE_MS = 1000
// 分片回包：超过这么久还没收齐就丢弃（防止半截分片常驻内存）
const CHUNK_STALE_MS = 30000
const LOG_MAX = 300

const _pending = {} // id -> { resolve, reject, timer, onTimeout }
const _chunks = {}  // id -> { parts: [], got, total, at }
let _reqId = 0
let _keepaliveTimer = null

function brief(value) {
  let text
  try {
    text = typeof value === 'string' ? value : JSON.stringify(value)
  } catch (e) {
    return '(unserializable ' + typeof value + ')'
  }
  if (!text) return String(text)
  return text.length > LOG_MAX ? text.slice(0, LOG_MAX) + '…(' + text.length + ' chars)' : text
}

function hasPending() {
  return Object.keys(_pending).length > 0
}

/** （重新）开始计时：分片回包每到一片就续一次，大回包不会被总超时误杀。 */
function armTimer(id) {
  const entry = _pending[id]
  if (!entry) return
  if (entry.timer) clearTimeout(entry.timer)
  entry.timer = setTimeout(() => {
    if (_pending[id] === entry) {
      delete _pending[id]
      entry.onTimeout()
    }
  }, entry.timeoutMs || API_TIMEOUT)
}

function genId() {
  _reqId += 1
  return 'r' + Date.now().toString(36) + '_' + _reqId
}

const _service = {
  _connect: null,
  _available: true,
  _ready: false,
  _onStatusChange: null,
  _onServerInfo: null,
  _serverInfo: null,
  _lastServerInfoAt: 0,
  _discoverResolvers: [],

  /**
   * Initialize the interconnect connection.
   * Follows the demo pattern: instance() → set callbacks → check getReadyState() once.
   * @param {Function} onStatusChange(ready: boolean)
   */
  init(onStatusChange) {
    if (onStatusChange !== undefined) {
      this._onStatusChange = onStatusChange
    }
    if (this._connect) {
      if (this._ready && this._onStatusChange) this._onStatusChange(true)
      return this
    }
    this._serverInfo = null
    this._discoverResolvers = []

    this._setupConnection()
    return this
  },

  /**
   * Ensure a connection exists on THIS instance. If the app entry already
   * initialized a shared instance (global singleton), _connect is set and this
   * is a no-op. If this bundle copy is isolated, it connects via the system
   * interconnect.instance() (a system singleton), so the active page receives
   * server_info and its handlers fire.
   */
  ensureInit() {
    if (!this._connect && this._available) {
      this.init()
    }
    return this
  },

  /**
   * Register a handler that is called immediately whenever server_info
   * arrives (phone pushes it every 5s), so the UI can update right away
   * instead of waiting for the next discoverServer retry.
   * @param {Function} handler({host, port})
   */
  setServerInfoHandler(handler) {
    this._onServerInfo = handler
  },

  /**
   * Set up the interconnect connection instance and register callbacks.
   * Called by init() and by _reconnect() when the channel expires.
   *
   * This matches the demo's handleCreateConn() pattern:
   *   conn = interconnect.instance()
   *   conn.getReadyState({ success, fail })
   *   conn.onmessage = ...
   *   conn.onopen = ...
   *   conn.onclose = ...
   *   conn.onerror = ...
   */
  _setupConnection() {
    let connect = null
    try {
      if (interconnect && typeof interconnect.instance === 'function') {
        connect = interconnect.instance()
      }
    } catch (e) {
      console.warn('[interconnect] instance() failed', e)
      connect = null
    }

    if (!connect) {
      console.warn('[interconnect] instance() returned null, feature not available')
      this._available = false
      this._ready = false
      this._connect = null
      if (this._onStatusChange) this._onStatusChange(false)
      return
    }

    this._connect = connect
    console.log('[interconnect] connection instance obtained')

    // Check ready state once (like the demo)
    try {
      connect.getReadyState({
        success: (data) => {
          if (data && data.status === 1) {
            console.log('[interconnect] getReadyState: already connected')
            this._ready = true
            if (this._onStatusChange) this._onStatusChange(true)
            // IMPORTANT: If already connected, send discover immediately
            // (onopen won't fire for existing connections)
            setTimeout(() => {
              if (this._discoverResolvers.length > 0) {
                console.log('[interconnect] Sending discover (already connected)')
                this._rawSend({ type: 'discover' })
              }
            }, 100)
          } else {
            console.log('[interconnect] getReadyState: status=' + (data && data.status) + ', waiting for onopen')
          }
        },
        fail: (data, code) => {
          console.warn('[interconnect] getReadyState fail: code=' + code)
        }
      })
    } catch (e) {
      console.warn('[interconnect] getReadyState error', e)
    }

    // Set up callbacks (exactly like the demo)
    connect.onopen = (data) => {
      console.log('[interconnect] onopen, isReconnected=' + (data && data.isReconnected))
      this._ready = true
      if (this._onStatusChange) this._onStatusChange(true)
      // Always try to send discover on connect — the phone side will respond
      // with server_info if it's listening. Use a short delay to ensure
      // the connection is fully ready.
      setTimeout(() => {
        if (this._discoverResolvers.length > 0) {
          console.log('[interconnect] Sending discover (onopen, ' + this._discoverResolvers.length + ' resolvers)')
          this._rawSend({ type: 'discover' })
        } else {
          console.log('[interconnect] onopen: no pending resolvers, sending discover anyway')
          this._rawSend({ type: 'discover' })
        }
      }, 200)
    }

    connect.onclose = (data) => {
      console.log('[interconnect] onclose, code=' + (data && data.code))
      this._ready = false
      if (this._onStatusChange) this._onStatusChange(false)
      this._notifyServerOffline()
    }

    connect.onerror = (data) => {
      const code = data && data.code
      console.warn('[interconnect] onerror: code=' + code)
      this._ready = false
      if (this._onStatusChange) this._onStatusChange(false)
      this._notifyServerOffline()

      if (code === 1001) {
        console.error('[interconnect] Companion app NOT INSTALLED on phone!')
      } else if (code === 1006) {
        console.warn('[interconnect] Connection disconnected')
      }
    }

    connect.onmessage = (data) => {
      this._handleMessage(data)
    }
  },

  _notifyServerOffline() {
    const hadServer = !!this._serverInfo
    this._serverInfo = null
    this._lastServerInfoAt = 0
    if (hadServer && this._onServerInfo) {
      try {
        this._onServerInfo(null)
      } catch (e) {
        console.warn('[interconnect] server offline handler error', e)
      }
    }
  },

  _handleMessage(data) {
    // Log the FULL raw structure so we can diagnose delivery issues
    try {
      console.log('[interconnect] onmessage raw: ' + brief(data))
    } catch (e) {
      console.log('[interconnect] onmessage raw (unserializable): ' + typeof data)
    }
    if (!data) return
    let msg = null
    // Try multiple field paths — the payload may arrive as data.data,
    // data.message, data.msg, or data itself depending on SDK version.
    const raw = data.data !== undefined ? data.data
      : (data.message !== undefined ? data.message
        : (data.msg !== undefined ? data.msg : data))
    try {
      if (typeof raw === 'string') {
        msg = JSON.parse(raw)
      } else if (typeof raw === 'object') {
        msg = raw
      } else {
        console.warn('[interconnect] unexpected message payload type: ' + typeof raw)
        return
      }
    } catch (e) {
      console.warn('[interconnect] failed to parse message: ' + raw, e)
      return
    }
    if (!msg || typeof msg !== 'object') return

    if (msg.type === 'chunk') {
      this._handleChunk(msg)
      return
    }

    console.log('[interconnect] parsed message: ' + brief(msg))

    if (msg.type === 'server_info') {
      this._ready = true
      this._lastServerInfoAt = Date.now()
      this._serverInfo = {
        host: msg.host || 'localhost',
        port: msg.port || 9000,
        name: msg.name || ''
      }
      console.log('[interconnect] server_info received: ' + JSON.stringify(this._serverInfo))
      const resolvers = this._discoverResolvers
      this._discoverResolvers = []
      resolvers.forEach((resolve) => resolve(this._serverInfo))
      // Notify the UI immediately if a handler is registered
      if (this._onServerInfo) {
        try {
          this._onServerInfo(this._serverInfo)
        } catch (e) {
          console.warn('[interconnect] server info handler error', e)
        }
      }
    } else if (msg.type === 'server_offline') {
      console.log('[interconnect] server_offline received')
      this._notifyServerOffline()
    } else if (msg.type === 'api_response') {
      const id = msg.id
      const pending = _pending[id]
      if (pending) {
        delete _pending[id]
        if (pending.timer) clearTimeout(pending.timer)
        if (msg.error) {
          pending.reject(msg.error)
        } else {
          pending.resolve(msg.data)
        }
      }
    } else if (msg.type === 'lan_devices') {
      const id = msg.id
      const pending = _pending[id]
      if (pending) {
        delete _pending[id]
        if (pending.timer) clearTimeout(pending.timer)
        pending.resolve(msg)
      }
    } else if (msg.type === 'proxy_http_response') {
      const id = msg.id
      const pending = _pending[id]
      if (pending) {
        delete _pending[id]
        if (pending.timer) clearTimeout(pending.timer)
        if (msg.error) {
          pending.reject(msg.error)
        } else {
          pending.resolve(msg.data)
        }
      }
    }
  },

  /**
   * 分片回包：手机把超过单条消息上限的回包拆成
   * `{type:'chunk', id, seq, total, data:'<原 JSON 文本的一段>'}`，这里按 seq 拼回
   * 原文再当成一条普通消息处理。每到一片就给对应请求续一次超时。
   */
  _handleChunk(msg) {
    const now = Date.now()
    Object.keys(_chunks).forEach((k) => {
      if (now - _chunks[k].at > CHUNK_STALE_MS) delete _chunks[k]
    })
    const id = msg.id
    const total = Number(msg.total) || 0
    const seq = Number(msg.seq)
    if (!id || total <= 0 || !(seq >= 0 && seq < total) || typeof msg.data !== 'string') return
    let buf = _chunks[id]
    if (!buf || buf.total !== total) {
      buf = { parts: new Array(total), got: 0, total: total, at: now }
      _chunks[id] = buf
    }
    buf.at = now
    if (buf.parts[seq] === undefined) {
      buf.parts[seq] = msg.data
      buf.got += 1
    }
    armTimer(id)
    if (buf.got < total) return
    delete _chunks[id]
    const joined = buf.parts.join('')
    console.log('[interconnect] chunked message reassembled: id=' + id + ' parts=' + total + ' chars=' + joined.length)
    this._handleMessage({ data: joined })
  },

  /** 有请求在途就每秒发一条 keepalive，让手机端别被系统冻结（见 KEEPALIVE_MS）。 */
  _startKeepalive() {
    if (_keepaliveTimer) return
    const tick = () => {
      _keepaliveTimer = null
      if (!hasPending() || !this._ready) return
      this._rawSend({ type: 'keepalive' }).catch(() => {})
      _keepaliveTimer = setTimeout(tick, KEEPALIVE_MS)
    }
    _keepaliveTimer = setTimeout(tick, KEEPALIVE_MS)
  },

  /**
   * 发一条带 id 的请求并等回包（api_request / proxy_http 共用）。
   * 回包可能分片到达；超时从最后一片到达时重新计时。
   */
  _request(message, timeoutLabel, timeoutMs) {
    return new Promise((resolve, reject) => {
      if (!this._available) {
        reject(new Error('interconnect not available'))
        return
      }
      if (!this._ready) {
        reject(new Error('interconnect not ready'))
        return
      }
      const id = genId()
      _pending[id] = {
        resolve,
        reject,
        timer: null,
        timeoutMs: timeoutMs || API_TIMEOUT,
        onTimeout: () => {
          delete _chunks[id]
          reject(new Error(timeoutLabel))
        }
      }
      armTimer(id)
      this._startKeepalive()
      message.id = id
      this._rawSend(message).catch((e) => {
        const entry = _pending[id]
        if (entry) {
          if (entry.timer) clearTimeout(entry.timer)
          delete _pending[id]
          reject(e)
        }
      })
    })
  },

  /**
   * Send a JS object through interconnect.
   * Per Vela docs, the send() data param is of type Object.
   *
   * IMPORTANT: The demo's fail callback uses (data) where data.code has the error code.
   * We check both the first param's .code and the second param for robustness.
   */
  _rawSend(obj) {
    return new Promise((resolve, reject) => {
      if (!this._connect) {
        reject(new Error('interconnect not available'))
        return
      }
      try {
        if (obj.type !== 'keepalive') console.log('[interconnect] sending: ' + brief(obj))
        this._connect.send({
          data: obj,
          success: () => resolve(),
          fail: (fdata, code) => {
            // Demo pattern: fdata.code has the error code
            const errCode = code || (fdata && fdata.code)
            console.warn('[interconnect] send failed: code=' + errCode + ' data=' + JSON.stringify(fdata))
            this._ready = false
            this._notifyServerOffline()
            reject(new Error('send failed: code=' + errCode))
          }
        })
      } catch (e) {
        console.warn('[interconnect] send error', e)
        this._ready = false
        this._notifyServerOffline()
        reject(e)
      }
    })
  },

  /**
   * Discover the HydroLink server over interconnect.
   * If already connected, sends discover immediately.
   * If not yet connected, waits for onopen to fire (which auto-sends discover).
   * Resolves with { host, port }.
   */
  discoverServer() {
    this.ensureInit()
    return new Promise((resolve, reject) => {
      if (!this._available) {
        reject(new Error('interconnect not available'))
        return
      }
      if (this._serverInfo) {
        resolve(this._serverInfo)
        return
      }

      // Add resolver — will be resolved when server_info is received
      this._discoverResolvers.push(resolve)

      // If already ready, send discover now with a small delay to ensure
      // the resolver is registered before any async callbacks fire
      if (this._ready) {
        console.log('[interconnect] discoverServer: already ready, sending discover')
        setTimeout(() => {
          if (this._discoverResolvers.length > 0) {
            this._rawSend({ type: 'discover' }).catch((e) => {
              const idx = this._discoverResolvers.indexOf(resolve)
              if (idx >= 0) this._discoverResolvers.splice(idx, 1)
              reject(e)
            })
          }
        }, 50)
      } else {
        console.log('[interconnect] discoverServer: not ready, waiting for onopen')
      }
      // If not ready, onopen will auto-send discover when connection establishes

      // Timeout
      setTimeout(() => {
        const idx = this._discoverResolvers.indexOf(resolve)
        if (idx >= 0) {
          this._discoverResolvers.splice(idx, 1)
          reject(new Error('discover timeout'))
        }
      }, DISCOVER_TIMEOUT)
    })
  },

  /**
   * Send an API request over interconnect and wait for the response.
   * @param {string} path   e.g. "/api/status"
   * @param {string} method "GET" | "POST"
   * @param {object} body   request body
   * @returns {Promise<object>} response data
   */
  sendApiRequest(path, method, body) {
    this.ensureInit()
    return this._request({
      type: 'api_request',
      path: path,
      method: method || 'GET',
      body: body || null
    }, 'api request timeout: ' + path)
  },

  /**
   * Ask the phone to perform an HTTP request on the watch's behalf
   * ("use interconnect to proxy http"). The phone fetches `url` and returns
   * { code, data } where code is the HTTP status and data is the parsed JSON.
   * @param {string} url    full URL, e.g. "http://192.168.1.10:9000/api/ping"
   * @param {string} method GET|POST
   * @param {object} body   request body (optional)
   * @param {object} headers request headers (optional)
   * @param {object} options { binary: true } → resolves {code, contentType, base64}
   * @returns {Promise<{code: number, data: object}>}
   */
  sendProxyHttp(url, method, body, headers, options) {
    this.ensureInit()
    const message = {
      type: 'proxy_http',
      url: url,
      method: method || 'GET',
      body: body || null,
      headers: headers || null
    }
    // binary：手机不按 JSON 解析，回 {code, contentType, base64}（图片/GIF 分段用）
    if (options && options.binary) message.binary = true
    return this._request(message, 'proxy request timeout: ' + url, options && options.timeoutMs)
  },

  /** Returns true when interconnect is connected and usable. */
  isReady() {
    return this._available && this._ready
  },

  /** Returns cached server_info or null when offline. */
  getServerInfo() {
    return this._ready ? this._serverInfo : null
  },

  /**
   * Liveness check for the interconnect companion server.
   * Phone pushes server_info every 5s and replies immediately to discover.
   * If we haven't heard from the phone in >6s, send a discover probe;
   * if >11s has elapsed with no reply, mark server offline.
   */
  checkAlive() {
    if (!this._available || !this._ready || !this._connect || !this._serverInfo) {
      if (this._serverInfo) this._notifyServerOffline()
      return false
    }
    const elapsed = this._lastServerInfoAt ? (Date.now() - this._lastServerInfoAt) : 0
    if (elapsed > 11000) {
      console.warn('[interconnect] heartbeat timeout (' + elapsed + 'ms), marking offline')
      this._notifyServerOffline()
      return false
    }
    if (elapsed > 6000) {
      this._rawSend({ type: 'discover' }).catch(() => {})
    }
    return true
  },

  /**
   * 请求手机端在局域网内通过 mDNS + Probe 扫描其它 HydroLink 设备。
   * @returns {Promise<Array<{name: string, host: string, port: number, device_type: string}>>}
   */
  discoverLan() {
    return new Promise((resolve) => {
      if (!this._available || !this._ready) {
        resolve([])
        return
      }
      const id = genId()
      const entry = {
        resolve: (data) => resolve((data && data.devices) || []),
        reject: () => resolve([]),
        timer: null
      }
      _pending[id] = entry
      entry.timer = setTimeout(() => {
        if (_pending[id]) {
          delete _pending[id]
          resolve([])
        }
      }, 5000)

      this._rawSend({
        type: 'discover_lan',
        id: id
      }).catch(() => {
        if (_pending[id]) {
          if (_pending[id].timer) clearTimeout(_pending[id].timer)
          delete _pending[id]
          resolve([])
        }
      })
    })
  },

  /** Returns true when the interconnect feature itself is present. */
  isAvailable() {
    return this._available
  },

  /** Snapshot of current interconnect state (for a debug page). */
  getState() {
    return {
      available: this._available,
      ready: this._ready,
      hasConnection: !!this._connect,
      serverInfo: this._serverInfo ? JSON.stringify(this._serverInfo) : 'null',
      pendingDiscover: this._discoverResolvers.length
    }
  },

  /** Release resources. Rejects any pending requests. */
  destroy() {
    this._onStatusChange = null
    this._discoverResolvers = []
    Object.keys(_pending).forEach((k) => {
      const entry = _pending[k]
      if (entry.timer) clearTimeout(entry.timer)
      entry.reject(new Error('interconnect service destroyed'))
      delete _pending[k]
    })
    this._connect = null
    this._ready = false
    this._serverInfo = null
  }
}

// The QuickApp build emits the app entry bundle and each page bundle as separate
// module scopes, so a plain module singleton would be duplicated (one copy per
// bundle). The connection created by app.ux's init() would live on the app-bundle
// copy, while the pages' copies would never connect — which is exactly why the
// home/debug pages report no connection even though the phone pushes server_info.
//
// Store ONE service instance on the shared JS global so every bundle copy
// resolves to the same object (same _connect, callbacks, _serverInfo, _pending).
const _GLOBAL_KEY = '__hydro_core_interconnect'

function _resolveSharedInstance() {
  const sharedRoot =
    (typeof globalThis !== 'undefined' && Object.getPrototypeOf(globalThis)) || Object
  let existing = null
  try {
    existing =
      Object[_GLOBAL_KEY] ||
      (sharedRoot && sharedRoot[_GLOBAL_KEY]) ||
      (typeof globalThis !== 'undefined' ? globalThis[_GLOBAL_KEY] : null)
  } catch (e) {}
  const inst = existing || _service
  try { Object[_GLOBAL_KEY] = inst } catch (e) {}
  try { if (sharedRoot) sharedRoot[_GLOBAL_KEY] = inst } catch (e) {}
  if (typeof globalThis !== 'undefined') {
    try { globalThis[_GLOBAL_KEY] = inst } catch (e) {}
  }
  return inst
}

export default _resolveSharedInstance()

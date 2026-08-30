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

const _pending = {} // id -> { resolve, reject, timer }
let _reqId = 0

function genId() {
  _reqId += 1
  return 'r' + Date.now().toString(36) + '_' + _reqId
}

export default {
  _connect: null,
  _available: true,
  _ready: false,
  _onStatusChange: null,
  _onServerInfo: null,
  _serverInfo: null,
  _discoverResolvers: [],

  /**
   * Initialize the interconnect connection.
   * Follows the demo pattern: instance() → set callbacks → check getReadyState() once.
   * @param {Function} onStatusChange(ready: boolean)
   */
  init(onStatusChange) {
    this._onStatusChange = onStatusChange
    this._serverInfo = null
    this._discoverResolvers = []

    this._setupConnection()
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
    }

    connect.onerror = (data) => {
      const code = data && data.code
      console.warn('[interconnect] onerror: code=' + code)
      this._ready = false
      if (this._onStatusChange) this._onStatusChange(false)

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

  _handleMessage(data) {
    // Log the FULL raw structure so we can diagnose delivery issues
    try {
      console.log('[interconnect] onmessage raw: ' + JSON.stringify(data))
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

    console.log('[interconnect] parsed message: ' + JSON.stringify(msg))

    if (msg.type === 'server_info') {
      this._serverInfo = {
        host: msg.host || 'localhost',
        port: msg.port || 9000
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
    }
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
        console.log('[interconnect] sending: ' + JSON.stringify(obj))
        this._connect.send({
          data: obj,
          success: () => resolve(),
          fail: (fdata, code) => {
            // Demo pattern: fdata.code has the error code
            const errCode = code || (fdata && fdata.code)
            console.warn('[interconnect] send failed: code=' + errCode + ' data=' + JSON.stringify(fdata))
            reject(new Error('send failed: code=' + errCode))
          }
        })
      } catch (e) {
        console.warn('[interconnect] send error', e)
        reject(e)
      }
    })
  },

  /**
   * Discover the HydroStation server over interconnect.
   * If already connected, sends discover immediately.
   * If not yet connected, waits for onopen to fire (which auto-sends discover).
   * Resolves with { host, port }.
   */
  discoverServer() {
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
      const entry = { resolve, reject, timer: null }
      _pending[id] = entry
      entry.timer = setTimeout(() => {
        if (_pending[id]) {
          delete _pending[id]
          reject(new Error('api request timeout: ' + path))
        }
      }, API_TIMEOUT)

      this._rawSend({
        type: 'api_request',
        id: id,
        path: path,
        method: method || 'GET',
        body: body || null
      }).catch((e) => {
        if (_pending[id]) {
          if (_pending[id].timer) clearTimeout(_pending[id].timer)
          delete _pending[id]
          reject(e)
        }
      })
    })
  },

  /** Returns true when interconnect is connected and usable. */
  isReady() {
    return this._available && this._ready
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

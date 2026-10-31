import file from '@system.file'
import fetch from '@system.fetch'
import device from '@system.device'
import interconnectService from './interconnectService.js'
import settingsService from './settingsService.js'

const DEVICES_FILE = 'internal://files/devices.json'
const DEVICE_ID_FILE = 'internal://files/device_id.json'
const DEFAULT_PORT = 9000
const POLL_INTERVAL = 2000
const POLL_TIMEOUT = 60000

function readText(uri) {
  return new Promise((resolve, reject) => {
    file.readText({
      uri,
      success: (data) => resolve(data.text || ''),
      fail: (data, code) => reject({ data, code })
    })
  })
}

function writeText(uri, text) {
  return new Promise((resolve, reject) => {
    file.writeText({
      uri,
      text,
      encoding: 'UTF-8',
      success: resolve,
      fail: (data, code) => reject({ data, code })
    })
  })
}

function doFetch(url, method, data, headers) {
  return new Promise((resolve, reject) => {
    fetch.fetch({
      url,
      method,
      data: data ? JSON.stringify(data) : undefined,
      // charset 显式带上：手机端服务端（NanoHTTPD）对没有 charset 的请求体按
      // US-ASCII 解码，中文会变 U+FFFD（服务端另有兜底，见 android HttpServer）
      header: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, headers || {}),
      responseType: 'json',
      success: (res) => {
        try {
          const body = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
          resolve({ code: res.code, data: body })
        } catch (e) {
          resolve({ code: res.code, data: res.data })
        }
      },
      fail: (data, code) => reject({ data, code })
    })
  })
}

function getBaseUrl(host, port) {
  return `http://${host}:${port || DEFAULT_PORT}`
}

/**
 * Check if a host indicates an interconnect device.
 * Interconnect devices use 'localhost' as the host since
 * communication goes through the interconnect channel, not HTTP.
 */
/**
 * 页面级数据缓存：手表页面每次切走都会被销毁、切回来重建，如果每次都等接口回来
 * 才渲染，就会出现"切一下页面就闪一次加载中"。页面可以先**同步**读缓存把内容画出来，
 * 再在后台静默刷新（见各页 onShow）。
 *
 * key 用 类型 + 设备标识；只活在内存里，进程重启即失效（数据本身不敏感、丢了无妨）。
 */
const _pageCache = {}

function cacheKey(kind, id) {
  return kind + ':' + (id || 'default')
}

function putCache(kind, id, value) {
  _pageCache[cacheKey(kind, id)] = value
}

function peekCache(kind, id) {
  const v = _pageCache[cacheKey(kind, id)]
  return v === undefined ? null : v
}

function isInterconnect(host) {
  return host === 'localhost' || host === '127.0.0.1'
}

/**
 * Unified API request that supports both HTTP and interconnect transport.
 * For interconnect devices (host='localhost'), routes through interconnectService.
 * For HTTP devices:
 *  - by default, when interconnect is connected to the phone, the request is
 *    proxied through interconnect (the phone performs the HTTP request);
 *  - when there is no interconnect connection, or the "强制使用 fetch" setting
 *    is enabled, the watch uses @system.fetch directly.
 *
 * @returns {Promise<{code: number, data: object}>}
 */
async function apiRequest(host, port, path, method, data, headers) {
  interconnectService.ensureInit()
  if (isInterconnect(host)) {
    try {
      const response = await interconnectService.sendApiRequest(path, method || 'GET', data)
      return { code: 200, data: response }
    } catch (e) {
      console.error('[deviceService] interconnect request failed: ' + path, e)
      return { code: 0, data: null }
    }
  }

  // HTTP device — proxy through interconnect when connected (unless forced fetch)
  const forceFetch = await settingsService.getForceFetch()
  if (!forceFetch && interconnectService.isReady()) {
    try {
      const url = getBaseUrl(host, port) + path
      const response = await interconnectService.sendProxyHttp(url, method || 'GET', data, headers)
      return { code: (response && response.code) || 0, data: response && response.data }
    } catch (e) {
      console.error('[deviceService] proxy request failed: ' + path, e)
      return { code: 0, data: null }
    }
  }

  const url = getBaseUrl(host, port) + path
  return doFetch(url, method, data, headers)
}

function generateDeviceUid() {
  return 'dev-' + Date.now().toString(36) + Math.random().toString(36).substr(2, 6)
}

// Stable lookup key for a device: use `id` if present, fall back to host:port
function getDeviceKey(device) {
  if (device.id) return device.id
  return (device.host || '') + ':' + (device.port || '')
}

function generateDeviceId() {
  return new Promise((resolve) => {
    let id = ''
    try {
      device.getDeviceId({
        success: (data) => {
          id = (data && data.deviceId) || ''
        },
        fail: () => {},
        complete: () => {
          if (!id) {
            id = 'hs-' + Date.now().toString(36) + Math.random().toString(36).substr(2, 8)
          }
          resolve(id)
        }
      })
    } catch (e) {
      id = 'hs-' + Date.now().toString(36) + Math.random().toString(36).substr(2, 8)
      resolve(id)
    }
  })
}

// getWatchName 从 device.getInfo 获取本手表设备型号名，严格且仅使用 product 字段
function getWatchName() {
  return new Promise((resolve) => {
    try {
      device.getInfo({
        success: (info) => {
          const product = (info && info.product ? String(info.product) : '').trim()
          resolve(product)
        },
        fail: () => resolve(''),
        complete: () => {}
      })
    } catch (e) {
      resolve('')
    }
  })
}

export default {
  // 页面缓存：切页面回来时先渲染，避免每次都闪"加载中"
  peekCache,
  putCache,

  /** Returns true if the given host uses interconnect transport. */
  isInterconnectDevice(host) {
    return isInterconnect(host)
  },

  async readDevices() {
    try {
      const text = await readText(DEVICES_FILE)
      return JSON.parse(text || '[]')
    } catch (e) {
      if (e.code === 301) return []
      console.error('[deviceService] readDevices failed', e)
      return []
    }
  },

  async getDevices() {
    return this.readDevices()
  },

  async writeDevices(devices) {
    try {
      await writeText(DEVICES_FILE, JSON.stringify(devices))
      return true
    } catch (e) {
      console.error('[deviceService] writeDevices failed', e)
      return false
    }
  },

  async getDeviceId() {
    try {
      const text = await readText(DEVICE_ID_FILE)
      const obj = JSON.parse(text || '{}')
      if (obj.deviceId) return obj.deviceId
    } catch (e) {
      // 文件不存在，继续生成
    }
    const id = await generateDeviceId()
    await writeText(DEVICE_ID_FILE, JSON.stringify({ deviceId: id }))
    return id
  },

  async ping(host, port) {
    try {
      const res = await apiRequest(host, port, '/api/ping', 'GET')
      return res.code === 200 && res.data && res.data.status === 'ok'
    } catch (e) {
      return false
    }
  },

  async pingWithTimeout(host, port, timeout) {
    const t = timeout || 3000
    return Promise.race([
      this.ping(host, port),
      new Promise((resolve) => setTimeout(() => resolve(false), t))
    ])
  },

  async requestPair(host, port, deviceId, deviceName) {
    try {
      const res = await apiRequest(host, port, '/api/pair', 'POST', {
        device_id: deviceId,
        device_name: deviceName || ''
      })
      return res.data
    } catch (e) {
      console.error('[deviceService] requestPair failed', e)
      return { status: 'error', message: '网络请求失败' }
    }
  },

  async checkPairStatus(host, port, deviceId) {
    try {
      const res = await apiRequest(host, port, '/api/check/' + deviceId, 'GET')
      return res.data
    } catch (e) {
      console.error('[deviceService] checkPairStatus failed', e)
      return { status: 'error', message: '网络请求失败' }
    }
  },

  async getServerSystemInfo(host, port, deviceId) {
    try {
      const res = await apiRequest(host, port, '/api/status', 'GET', null, { 'X-Device-ID': deviceId })
      if (res.code === 200 && res.data) {
        const battery = res.data.battery || {}
        return {
          hostname: (res.data.system && res.data.system.hostname) || '',
          hasBattery: !!battery.has_battery,
          batteryPercent: battery.percent || 0,
          batteryCharging: !!battery.charging,
          version: (res.data.system && res.data.system.version) || ''
        }
      }
    } catch (e) {
      console.error('[deviceService] getServerSystemInfo failed', e)
    }
    return { hostname: '', hasBattery: false, batteryPercent: 0, batteryCharging: false, version: '' }
  },

  determineDeviceType(sysInfo) {
    if (sysInfo.version && sysInfo.version.toLowerCase().includes('android')) {
      return 'cellphone'
    }
    if (sysInfo.hasBattery) {
      return 'laptop'
    }
    return 'desktop'
  },

  async resolveDeviceName(host, port, deviceId, deviceName) {
    const manualName = (deviceName || '').trim()
    if (manualName) return manualName
    const sysInfo = await this.getServerSystemInfo(host, port, deviceId)
    return sysInfo.hostname || host
  },

  async pairDevice(host, port, deviceName, onStatus) {
    const deviceId = await this.getDeviceId()
    const status = (msg) => { if (onStatus) onStatus(msg) }

    // 区分两个名称：
    //   watchName -> 作为 device_name 发送给服务端（即本手表的型号名称，由 getWatchName 严格且仅取 product）
    //   manualName (用户填写的服务器别名/局域网搜索到的主机名) -> 手表本地保存此服务端连接项时的名称，
    //                若为空则自动回退到服务端的 hostname。两者职责清晰，不再互相串用。
    const manualName = (deviceName || '').trim()
    const watchName = (await getWatchName()) || ''

    status('正在连接服务器...')
    const reachable = await this.ping(host, port)
    if (!reachable) {
      return { success: false, message: '无法连接到服务器，请检查IP和端口' }
    }

    status('正在发送配对请求...')
    const pairRes = await this.requestPair(host, port, deviceId, watchName)
    if (pairRes.status === 'trusted') {
      const sysInfo = await this.getServerSystemInfo(host, port, deviceId)
      const resolvedName = manualName || sysInfo.hostname || host
      const device = {
        id: generateDeviceUid(),
        name: resolvedName,
        host,
        port: port || DEFAULT_PORT,
        deviceId,
        deviceType: this.determineDeviceType(sysInfo),
        encryptionKey: pairRes.encryption_key || '',
        pairedAt: Date.now()
      }
      await this.saveDevice(device)
      return { success: true, device, message: '设备已信任' }
    }
    if (pairRes.status === 'rejected') {
      return { success: false, message: '设备已被拒绝' }
    }
    if (pairRes.status === 'error') {
      return { success: false, message: pairRes.message || '配对请求失败' }
    }

    status('等待服务器确认...')
    const startTime = Date.now()
    return new Promise((resolve) => {
      const poll = async () => {
        if (Date.now() - startTime > POLL_TIMEOUT) {
          resolve({ success: false, message: '配对超时，请重试' })
          return
        }
        const checkRes = await this.checkPairStatus(host, port, deviceId)
        if (checkRes.status === 'trusted') {
          const sysInfo = await this.getServerSystemInfo(host, port, deviceId)
          const resolvedName = manualName || sysInfo.hostname || host
          const device = {
            id: generateDeviceUid(),
            name: resolvedName,
            host,
            port: port || DEFAULT_PORT,
            deviceId,
            deviceType: this.determineDeviceType(sysInfo),
            encryptionKey: checkRes.encryption_key || '',
            pairedAt: Date.now()
          }
          await this.saveDevice(device)
          resolve({ success: true, device, message: '配对成功' })
        } else if (checkRes.status === 'rejected') {
          resolve({ success: false, message: '设备已被拒绝' })
        } else {
          setTimeout(poll, POLL_INTERVAL)
        }
      }
      setTimeout(poll, POLL_INTERVAL)
    })
  },

  async saveDevice(device) {
    if (!device.id) device.id = generateDeviceUid()
    const devices = await this.readDevices()
    const newId = device.id
    const hostPortKey = (device.host || '') + ':' + (device.port || '')

    let existingIdx = devices.findIndex(d => getDeviceKey(d) === newId)
    if (existingIdx < 0) {
      existingIdx = devices.findIndex(d => getDeviceKey(d) === hostPortKey)
    }

    if (existingIdx >= 0) {
      device.id = devices[existingIdx].id || newId
      devices[existingIdx] = device
    } else {
      devices.push(device)
    }
    await this.writeDevices(devices)
  },

  async updateDevice(id, updates) {
    const devices = await this.readDevices()
    const idx = devices.findIndex(d => getDeviceKey(d) === id)
    if (idx >= 0) {
      Object.assign(devices[idx], updates)
      await this.writeDevices(devices)
      return devices[idx]
    }
    return null
  },

  async getDevice(id) {
    const devices = await this.readDevices()
    return devices.find(d => getDeviceKey(d) === id) || null
  },

  async removeDevice(id) {
    const devices = await this.readDevices()
    const filtered = devices.filter(d => getDeviceKey(d) !== id)
    await this.writeDevices(filtered)
  },

  async sendCommand(host, port, deviceId, path, method) {
    try {
      const res = await apiRequest(host, port, path, method || 'POST', null, { 'X-Device-ID': deviceId })
      return res
    } catch (e) {
      console.error('[deviceService] sendCommand failed', e)
      return { code: 0, data: null }
    }
  },

  async getCommands(host, port, deviceId) {
    try {
      const res = await apiRequest(host, port, '/api/commands', 'GET', null, { 'X-Device-ID': deviceId })
      if (res.code === 200 && res.data && res.data.status === 'ok') {
        const list = res.data.commands || []
        putCache('commands', deviceId, list)
        return list
      }
    } catch (e) {
      console.error('[deviceService] getCommands failed', e)
    }
    return []
  },

  async runCommand(host, port, deviceId, cmdId) {
    try {
      const res = await apiRequest(host, port, '/api/commands/' + cmdId + '/run', 'POST', null, { 'X-Device-ID': deviceId })
      return res.data
    } catch (e) {
      console.error('[deviceService] runCommand failed', e)
      return { status: 'error', message: '网络请求失败' }
    }
  },

  async getPerformance(host, port, deviceId) {
    try {
      const res = await apiRequest(host, port, '/api/performance', 'GET', null, { 'X-Device-ID': deviceId })
      if (res.code === 200 && res.data && res.data.status === 'ok') {
        return res.data
      }
    } catch (e) {
      console.error('[deviceService] getPerformance failed', e)
    }
    return null
  },

  async getMusicInfo(host, port, deviceId) {
    try {
      const res = await apiRequest(host, port, '/api/music', 'GET', null, { 'X-Device-ID': deviceId })
      if (res.code === 200 && res.data && res.data.status === 'ok') {
        return res.data
      }
    } catch (e) {
      console.error('[deviceService] getMusicInfo failed', e)
    }
    return null
  },

  async controlMusic(host, port, deviceId, action) {
    try {
      const res = await apiRequest(host, port, '/api/music/control', 'POST', { action }, { 'X-Device-ID': deviceId })
      return res.data
    } catch (e) {
      console.error('[deviceService] controlMusic failed', e)
      return { status: 'error', message: '网络请求失败' }
    }
  },

  /**
   * HydroKit request: call /api/hydro/{...} on the server and return the raw
   * parsed body (the View JSON), routing over whatever transport the device
   * uses (interconnect / proxy / direct fetch).
   */
  async hydro(host, port, path, method, body, headers) {
    try {
      let reqPath = path
      let reqBody = body
      const m = method || 'POST'
      interconnectService.ensureInit()
      // 互联通道（host=localhost 或手表已连上手机）没有 HTTP 头，
      // X-Hydro-Store 会丢 —— 插件 watch 作用域状态就没了。改为放进
      // GET 的 query 或 POST 的 body.store；服务端两条路都认。
      if ((isInterconnect(host) || interconnectService.isReady()) && headers && headers['X-Hydro-Store']) {
        const raw = headers['X-Hydro-Store']
        if (m === 'GET') {
          reqPath = path + (path.indexOf('?') >= 0 ? '&' : '?') + 'store=' + encodeURIComponent(raw)
        } else {
          let parsed = {}
          try {
            parsed = JSON.parse(raw) || {}
          } catch (e) {
            parsed = {}
          }
          reqBody = Object.assign({}, body || {}, { store: parsed })
        }
      }
      const res = await apiRequest(host, port, reqPath, m, reqBody, headers)
      return res.data
    } catch (e) {
      console.error('[deviceService] hydro request failed: ' + path, e)
      return null
    }
  },

  /** Plugin watch-side storage (scope:'watch') — persisted on the device. */
  _pluginStoreUri(pkg) {
    return 'internal://files/hydro_store_' + String(pkg).replace(/[^A-Za-z0-9_.-]/g, '_') + '.json'
  },

  async readPluginStore(pkg) {
    try {
      const text = await readText(this._pluginStoreUri(pkg))
      return text ? JSON.parse(text) : {}
    } catch (e) {
      return {}
    }
  },

  async writePluginStore(pkg, obj) {
    try {
      await writeText(this._pluginStoreUri(pkg), JSON.stringify(obj || {}))
      return true
    } catch (e) {
      console.error('[deviceService] writePluginStore failed', pkg, e)
      return false
    }
  }
}

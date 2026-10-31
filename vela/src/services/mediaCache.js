/**
 * mediaCache
 *
 * 让 `<image>` 能显示"只有经互联通道才拿得到"的远程图。
 *
 * 手表默认走互联代理（不强制 fetch）时，手表自己往往连不到宿主的局域网地址，
 * 直接把远程图片 URL 塞给 `<image src>` 只会得到一个感叹号。这里把这类 URL
 * 经手机代理下载（`proxy_http` + `binary`，回 base64，大文件由手机分片），
 * 写进 `internal://cache/hkm_<hash>.<ext>`，再把本地 uri 交给 image。
 *
 *  - 强制 fetch 或互联未连上：原样返回 URL（手表自己取）
 *  - 串行队列（高优先级的先走，预取与普通配图排后面）
 *  - 同一 URL 并发只下一次；成功的留在 LRU（最多 MAX_FILES 个文件），挤出去的删文件
 *  - 失败 reject，由调用方自行处理
 *
 * 和 interconnectService 一样挂在 globalThis 上：每个页面 bundle 各有一份模块拷贝，
 * 缓存表和文件必须只有一份。
 */
import file from '@system.file'
import interconnectService from './interconnectService.js'
import settingsService from './settingsService.js'

const CACHE_DIR = 'internal://cache/'
const PREFIX = 'hkm_'
const MAX_FILES = 16
const DOWNLOAD_TIMEOUT = 20000

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
const B64_LOOKUP = (() => {
  const table = new Uint8Array(256).fill(255)
  for (let i = 0; i < B64.length; i++) table[B64.charCodeAt(i)] = i
  table['-'.charCodeAt(0)] = 62
  table['_'.charCodeAt(0)] = 63
  return table
})()

export function decodeBase64(text) {
  const clean = String(text || '').replace(/[^A-Za-z0-9+/\-_]/g, '')
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4))
  let n = 0
  let acc = 0
  let bits = 0
  for (let i = 0; i < clean.length; i++) {
    const v = B64_LOOKUP[clean.charCodeAt(i)]
    if (v === 255) continue
    acc = (acc << 6) | v
    bits += 6
    if (bits >= 8) {
      bits -= 8
      out[n++] = (acc >> bits) & 0xff
    }
  }
  return n === out.length ? out : out.subarray(0, n)
}

function hashUrl(url) {
  let h = 5381
  for (let i = 0; i < url.length; i++) h = ((h * 33) ^ url.charCodeAt(i)) >>> 0
  return h.toString(36) + '_' + url.length.toString(36)
}

function extOf(url, contentType) {
  const ct = String(contentType || '').toLowerCase()
  if (ct.indexOf('gif') >= 0) return 'gif'
  if (ct.indexOf('png') >= 0) return 'png'
  if (ct.indexOf('jpeg') >= 0 || ct.indexOf('jpg') >= 0) return 'jpg'
  if (ct.indexOf('webp') >= 0) return 'webp'
  const m = /\.(gif|png|jpe?g|webp|bmp)(?:[?#]|$)/i.exec(url)
  return m ? m[1].toLowerCase() : 'bin'
}

function writeFile(uri, buffer) {
  return new Promise((resolve, reject) => {
    file.writeArrayBuffer({
      uri: uri,
      buffer: buffer,
      success: () => resolve(uri),
      fail: (data, code) => reject(new Error('写缓存失败 ' + code + ': ' + uri))
    })
  })
}

function deleteFile(uri) {
  try {
    file.delete({ uri: uri, success: () => {}, fail: () => {} })
  } catch (e) {}
}

const _cache = {
  _entries: [], // [{ url, uri }]，越靠后越新
  _inflight: {}, // url -> { promise, priority }
  _queue: [], // [{ url, priority, run }]
  _busy: false,
  _swept: false,

  /** 上次运行残留的缓存文件：第一次用到时清掉。 */
  _sweep() {
    if (this._swept) return
    this._swept = true
    try {
      file.list({
        uri: CACHE_DIR,
        success: (data) => {
          const keep = {}
          this._entries.forEach((e) => { keep[e.uri] = true })
          ;((data && data.fileList) || []).forEach((f) => {
            const uri = f && f.uri
            if (uri && uri.indexOf(CACHE_DIR + PREFIX) === 0 && !keep[uri]) deleteFile(uri)
          })
        },
        fail: () => {}
      })
    } catch (e) {}
  },

  /** 同步查已缓存的本地 uri（命中会顺便刷新 LRU 顺序）。 */
  peek(url) {
    if (!url) return null
    for (let i = 0; i < this._entries.length; i++) {
      if (this._entries[i].url === url) {
        const entry = this._entries.splice(i, 1)[0]
        this._entries.push(entry)
        return entry.uri
      }
    }
    return null
  },

  _remember(url, uri) {
    this._entries.push({ url: url, uri: uri })
    while (this._entries.length > MAX_FILES) deleteFile(this._entries.shift().uri)
  },

  async _shouldProxy(url) {
    if (!/^https?:\/\//i.test(url)) return false
    interconnectService.ensureInit()
    if (!interconnectService.isReady()) return false
    try {
      return !(await settingsService.getForceFetch())
    } catch (e) {
      return true
    }
  },

  async _download(url) {
    const res = await interconnectService.sendProxyHttp(url, 'GET', null, null, {
      binary: true,
      timeoutMs: DOWNLOAD_TIMEOUT
    })
    const code = (res && res.code) || 0
    if (code < 200 || code >= 300 || !res.base64) {
      throw new Error('代理取图失败 ' + code + (res && res.error ? ': ' + res.error : '') + ' ' + url)
    }
    const bytes = decodeBase64(res.base64)
    const uri = CACHE_DIR + PREFIX + hashUrl(url) + '.' + extOf(url, res.contentType)
    await writeFile(uri, bytes)
    console.log('[mediaCache] cached ' + bytes.length + 'B -> ' + uri)
    return uri
  },

  _pump() {
    if (this._busy || !this._queue.length) return
    let bestIdx = 0
    for (let i = 1; i < this._queue.length; i++) {
      if (this._queue[i].priority > this._queue[bestIdx].priority) bestIdx = i
    }
    const task = this._queue.splice(bestIdx, 1)[0]
    this._busy = true
    task.run().then(
      () => {
        this._busy = false
        this._pump()
      },
      () => {
        this._busy = false
        this._pump()
      }
    )
  },

  /** URL → 可以直接交给 `<image src>` 的地址（本地缓存 uri 或原 URL）。 */
  async resolve(url, priority) {
    if (!url || typeof url !== 'string') return url
    if (!(await this._shouldProxy(url))) return url
    this._sweep()
    const hit = this.peek(url)
    if (hit) return hit
    const prio = typeof priority === 'number' ? priority : 1
    const existing = this._inflight[url]
    if (existing) {
      if (prio > existing.priority) {
        existing.priority = prio
        for (let i = 0; i < this._queue.length; i++) {
          if (this._queue[i].url === url && prio > this._queue[i].priority) {
            this._queue[i].priority = prio
          }
        }
      }
      return existing.promise
    }
    let resolveOuter
    let rejectOuter
    const promise = new Promise((resolve, reject) => {
      resolveOuter = resolve
      rejectOuter = reject
    })
    this._inflight[url] = { promise: promise, priority: prio }
    this._queue.push({
      url: url,
      priority: prio,
      run: () =>
        this._download(url).then(
          (uri) => {
            delete this._inflight[url]
            this._remember(url, uri)
            resolveOuter(uri)
          },
          (e) => {
            delete this._inflight[url]
            rejectOuter(e)
          }
        )
    })
    this._pump()
    return promise
  },

  /** 预取（低优先级，不抢当前正在播的段）。 */
  prefetch(url) {
    this.resolve(url, 0).catch(() => {})
  }
}

const _GLOBAL_KEY = '__hydro_media_cache'

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
  const inst = existing || _cache
  try { Object[_GLOBAL_KEY] = inst } catch (e) {}
  try { if (sharedRoot) sharedRoot[_GLOBAL_KEY] = inst } catch (e) {}
  if (typeof globalThis !== 'undefined') {
    try { globalThis[_GLOBAL_KEY] = inst } catch (e) {}
  }
  return inst
}

export default _resolveSharedInstance()

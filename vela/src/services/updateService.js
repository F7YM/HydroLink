/**
 * updateService.js
 *
 * Vela 端检查更新服务：
 *  - 按自动镜像源顺序依次请求 app.json（优先走蓝牙互联代理，回退 @system.fetch）
 *  - 解析出 Vela 端专属更新日志、网盘链接与强制更新状态
 */
import fetch from '@system.fetch'
import interconnectService from './interconnectService.js'
import settingsService from './settingsService.js'
import {
  getAppJsonUrls,
  parseUpdateInfo,
  isNewer,
  isForceUpdate,
  todayDateString,
  shouldAutoCheck,
  shouldPrompt
} from './updateHelper.js'

let _autoCheckTriggered = false
let _latestUpdateCache = null

function fetchDirect(url) {
  return new Promise((resolve, reject) => {
    fetch.fetch({
      url,
      method: 'GET',
      responseType: 'text',
      success: (res) => {
        if (res && res.code === 200 && res.data) {
          resolve(res.data)
        } else {
          reject(new Error(`HTTP ${(res && res.code) || 0}`))
        }
      },
      fail: (data, code) => reject(new Error(`fetch failed: ${code}`))
    })
  })
}

async function fetchUrl(url, forceFetch) {
  interconnectService.ensureInit()
  if (!forceFetch && interconnectService.isReady()) {
    try {
      const res = await interconnectService.sendProxyHttp(url, 'GET', null, null)
      if (res && res.code === 200 && res.data != null) {
        return res.data
      }
    } catch (e) {
      // 互联代理失败时继续尝试直连 fetch
    }
  }
  return fetchDirect(url)
}

export default {
  getCachedUpdate() {
    return _latestUpdateCache
  },

  setCachedUpdate(info) {
    _latestUpdateCache = info || null
  },

  /**
   * 依次尝试全部镜像源获取最新 app.json。
   * 全部失败时返回 null。
   */
  async fetchUpdate() {
    const forceFetch = await settingsService.getForceFetch()
    const urls = getAppJsonUrls()
    for (let i = 0; i < urls.length; i++) {
      try {
        const raw = await fetchUrl(urls[i], forceFetch)
        const info = parseUpdateInfo(raw)
        if (info && info.version) {
          _latestUpdateCache = info
          return info
        }
      } catch (e) {
        // 尝试下一个镜像源
      }
    }
    return null
  },

  /**
   * 手动检查更新（由版本信息页触发）。
   * 返回 { status: 'error' | 'latest' | 'update', update, isForce }
   */
  async checkManual(currentVersion) {
    const update = await this.fetchUpdate()
    if (!update) {
      return { status: 'error', update: null, isForce: false }
    }
    const newer = isNewer(update.version, currentVersion)
    const force = isForceUpdate(update, currentVersion)
    if (!newer) {
      return { status: 'latest', update, isForce: false }
    }
    return { status: 'update', update, isForce: force }
  },

  /**
   * 启动自动检查更新（由首页触发，单次应用会话成功检查一次即可）。
   * 返回 { checked: boolean, prompt: { update, isForce } | null }。
   */
  async checkAutoIfNeeded(currentVersion) {
    if (_autoCheckTriggered) return { checked: true, prompt: null }
    _autoCheckTriggered = true

    const today = todayDateString()
    const update = await this.fetchUpdate()
    if (!update) {
      _autoCheckTriggered = false
      return { checked: false, prompt: null }
    }

    const newer = isNewer(update.version, currentVersion)
    if (!newer) {
      await settingsService.setLastUpdateCheckDate(today)
      return { checked: true, prompt: null }
    }

    const force = isForceUpdate(update, currentVersion)
    if (force) {
      return { checked: true, prompt: { update, isForce: true } }
    }

    const dates = await settingsService.getUpdateDates()
    if (
      shouldAutoCheck(dates.lastUpdateCheckDate, today) &&
      shouldPrompt(dates.lastUpdateIgnoreDate, today)
    ) {
      await settingsService.setLastUpdateCheckDate(today)
      return { checked: true, prompt: { update, isForce: false } }
    }

    return { checked: true, prompt: null }
  },

  /** 记录今日忽略更新。 */
  async ignoreToday() {
    const today = todayDateString()
    await settingsService.setLastUpdateIgnoreDate(today)
  }
}

/**
 * settingsService
 *
 * Persists watch-side settings to internal://files/settings.json.
 * Stored via @system.file (shared across page bundles through the file system,
 * so module duplication between bundles does not break state).
 */
import file from '@system.file'

const SETTINGS_FILE = 'internal://files/settings.json'
const DEFAULTS = {
  forceFetch: false,
  keepScreenOn: false,
  oobeCompleted: false,
  hydroAppsStyle: 'grid',
  lastUpdateCheckDate: '',
  lastUpdateIgnoreDate: ''
}

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

async function getSettings() {
  try {
    const text = await readText(SETTINGS_FILE)
    const parsed = JSON.parse(text || '{}')
    return Object.assign({}, DEFAULTS, parsed)
  } catch (e) {
    return Object.assign({}, DEFAULTS)
  }
}

export default {
  /** Returns true when HTTP requests must use @system.fetch directly (no proxy). */
  async getForceFetch() {
    const s = await getSettings()
    return !!s.forceFetch
  },

  /** Persist the force-fetch setting. */
  async setForceFetch(value) {
    const s = await getSettings()
    s.forceFetch = !!value
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.forceFetch
  },

  /** 屏幕常亮（默认关）：开着时手表在使用本应用期间不自动息屏。 */
  async getKeepScreenOn() {
    const s = await getSettings()
    return !!s.keepScreenOn
  },

  /** 记住屏幕常亮开关。 */
  async setKeepScreenOn(value) {
    const s = await getSettings()
    s.keepScreenOn = !!value
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.keepScreenOn
  },

  /** 开箱新手引导完成标志（默认 false）：首次启动时进入 OOBE。 */
  async getOobeCompleted() {
    const s = await getSettings()
    return !!s.oobeCompleted
  },

  /** 记住 OOBE 引导完成状态。 */
  async setOobeCompleted(value) {
    const s = await getSettings()
    s.oobeCompleted = !!value
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.oobeCompleted
  },

  /** HydroApps 列表样式（默认 'grid'：'grid' 图标式 | 'list' 列表式）。 */
  async getHydroAppsStyle() {
    const s = await getSettings()
    return s.hydroAppsStyle === 'list' ? 'list' : 'grid'
  },

  /** 记住 HydroApps 列表样式。 */
  async setHydroAppsStyle(value) {
    const s = await getSettings()
    s.hydroAppsStyle = value === 'list' ? 'list' : 'grid'
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.hydroAppsStyle
  },

  /** 读取自动检查更新与忽略日期记录。 */
  async getUpdateDates() {
    const s = await getSettings()
    return {
      lastUpdateCheckDate: s.lastUpdateCheckDate || '',
      lastUpdateIgnoreDate: s.lastUpdateIgnoreDate || ''
    }
  },

  /** 记录最近一次自动检查更新的日期（yyyy-MM-dd）。 */
  async setLastUpdateCheckDate(dateStr) {
    const s = await getSettings()
    s.lastUpdateCheckDate = String(dateStr || '')
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.lastUpdateCheckDate
  },

  /** 记录最近一次点击「忽略」更新的日期（yyyy-MM-dd）。 */
  async setLastUpdateIgnoreDate(dateStr) {
    const s = await getSettings()
    s.lastUpdateIgnoreDate = String(dateStr || '')
    await writeText(SETTINGS_FILE, JSON.stringify(s))
    return s.lastUpdateIgnoreDate
  }
}

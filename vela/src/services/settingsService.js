/**
 * settingsService
 *
 * Persists watch-side settings to internal://files/settings.json.
 * Stored via @system.file (shared across page bundles through the file system,
 * so module duplication between bundles does not break state).
 */
import file from '@system.file'

const SETTINGS_FILE = 'internal://files/settings.json'
const DEFAULTS = { forceFetch: false }

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
  }
}

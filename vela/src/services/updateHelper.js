/**
 * updateHelper.js
 *
 * Vela 端应用更新纯函数模块（无 @system.* 依赖，可在 Node 单测中直接运行）：
 *  - 自动镜像源 URL 列表（对齐手机端 / 桌面端 MarketSources.Auto）
 *  - app.json 解析（仅提取 Vela / 手表端更新日志）
 *  - 语义化版本比对与强制更新判定
 *  - 每日自动检查频控与忽略判定
 */

export const MIRROR_BASES = [
  'https://gh-proxy.com/https://raw.githubusercontent.com/F7YM/hydromarket-index/main',
  'https://raw.githubusercontent.com/F7YM/hydromarket-index/main',
  'https://cdn.jsdelivr.net/gh/F7YM/hydromarket-index@main'
]

export function getAppJsonUrls(bases = MIRROR_BASES) {
  return (bases || MIRROR_BASES).map((base) => {
    const clean = String(base || '').replace(/\/+$/, '')
    return `${clean}/app.json`
  })
}

/**
 * 仅提取 Vela（手表端）的更新日志条目，并统一编号为 "1. xxx"、"2. xxx"。
 */
export function extractVelaChangelog(changelog) {
  if (!changelog) return ['常规稳定性修复与性能优化。']

  let rawLines = []
  if (Array.isArray(changelog)) {
    rawLines = changelog
  } else if (typeof changelog === 'string') {
    rawLines = changelog.split(/\r?\n/)
  } else if (typeof changelog === 'object') {
    const watchKeys = ['手表端', 'Vela端', 'vela', 'watch', 'Watch']
    let picked = null
    for (let i = 0; i < watchKeys.length; i++) {
      const k = watchKeys[i]
      if (Object.prototype.hasOwnProperty.call(changelog, k) && changelog[k] != null) {
        picked = changelog[k]
        break
      }
    }
    if (Array.isArray(picked)) {
      rawLines = picked
    } else if (typeof picked === 'string') {
      rawLines = picked.split(/\r?\n/)
    }
  }

  const cleaned = []
  for (let i = 0; i < rawLines.length; i++) {
    const line = String(rawLines[i] || '').trim()
    if (!line) continue
    cleaned.push(line)
  }

  if (cleaned.length === 0) {
    return ['常规稳定性修复与性能优化。']
  }

  return cleaned.map((line, idx) => {
    if (/^(\d+[.、]|[-•])/.test(line)) {
      return line
    }
    return `${idx + 1}. ${line}`
  })
}

/**
 * 解析 app.json 内容（兼容对象或 JSON 字符串）。
 */
export function parseUpdateInfo(raw) {
  const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (!obj || typeof obj !== 'object') {
    throw new Error('无法解析 app.json')
  }
  const version = String(obj.version || '').trim()
  if (!version) {
    throw new Error('缺少 version 字段')
  }
  return {
    version,
    versionCode: Number(obj.version_code) || 0,
    publishDate: String(obj.publish_date || '').trim(),
    title: String(obj.title || `HydroLink v${version}`).trim(),
    changelog: extractVelaChangelog(obj.changelog),
    downloadUrl: String(obj.download_url || '').trim(),
    backupUrl: String(obj.backup_url || '').trim(),
    password: String(obj.password || '').trim(),
    force: !!obj.force,
    minVersion: String(obj.min_version || '').trim()
  }
}

function parseVer(v) {
  const clean = String(v || '').trim().replace(/^[vV]/, '')
  const dashIdx = clean.indexOf('-')
  const mainPart = dashIdx >= 0 ? clean.slice(0, dashIdx) : clean
  const prePart = dashIdx >= 0 ? clean.slice(dashIdx + 1) : ''
  const nums = mainPart.split('.').map((seg) => {
    const n = parseInt(seg, 10)
    return Number.isNaN(n) ? 0 : n
  })
  return { nums, pre: prePart }
}

/**
 * 语义化版本比较（对齐 Android / Desktop 的 PluginStore.compareVersions）：
 *  - 缺失段按 0 补齐（1.3 == 1.3.0）
 *  - 正式版高于预发布版（1.3.0 > 1.3.0-beta）
 */
export function compareVersions(a, b) {
  const va = parseVer(a)
  const vb = parseVer(b)
  const len = Math.max(va.nums.length, vb.nums.length, 3)
  for (let i = 0; i < len; i++) {
    const na = va.nums[i] || 0
    const nb = vb.nums[i] || 0
    if (na !== nb) return na > nb ? 1 : -1
  }
  if (!va.pre && vb.pre) return 1
  if (va.pre && !vb.pre) return -1
  if (va.pre !== vb.pre) return va.pre > vb.pre ? 1 : -1
  return 0
}

export function isNewer(remoteVersion, currentVersion) {
  return compareVersions(remoteVersion, currentVersion) > 0
}

export function isForceUpdate(update, currentVersion) {
  if (!update) return false
  if (update.force) return true
  if (update.minVersion && String(update.minVersion).trim()) {
    return compareVersions(currentVersion, update.minVersion) < 0
  }
  return false
}

export function todayDateString(now = new Date()) {
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function shouldAutoCheck(lastCheckDate, today = todayDateString()) {
  return String(lastCheckDate || '') !== today
}

export function shouldPrompt(lastIgnoreDate, today = todayDateString()) {
  return String(lastIgnoreDate || '') !== today
}

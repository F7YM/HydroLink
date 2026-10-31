import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MIRROR_BASES,
  getAppJsonUrls,
  extractVelaChangelog,
  parseUpdateInfo,
  compareVersions,
  isNewer,
  isForceUpdate,
  shouldAutoCheck,
  shouldPrompt
} from './updateHelper.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// 1. 镜像源顺序校验
const urls = getAppJsonUrls()
assert.equal(urls.length, 3)
assert.equal(MIRROR_BASES.length, 3)
assert.ok(urls[0].startsWith('https://gh-proxy.com/'))
assert.ok(urls[0].endsWith('/app.json'))
assert.ok(urls[1].startsWith('https://raw.githubusercontent.com/'))
assert.ok(urls[2].startsWith('https://cdn.jsdelivr.net/'))

// 2. 仅提取手表端更新日志
const sampleAppJson = {
  version: '1.3.0',
  version_code: 7,
  publish_date: '2026-10-31',
  title: 'HydroLink v1.3.0',
  changelog: {
    手表端: ['重构渲染引擎', '支持蓝牙互联缓存图片'],
    手机端: ['不应出现在手表端更新日志中'],
    桌面端: ['桌面端日志也不应出现']
  },
  download_url: 'https://pan.quark.cn/s/522d9896a3e8',
  password: '1234',
  force: false
}

const parsed = parseUpdateInfo(JSON.stringify(sampleAppJson))
assert.equal(parsed.version, '1.3.0')
assert.equal(parsed.versionCode, 7)
assert.equal(parsed.publishDate, '2026-10-31')
assert.equal(parsed.downloadUrl, 'https://pan.quark.cn/s/522d9896a3e8')
assert.equal(parsed.password, '1234')
assert.deepEqual(parsed.changelog, [
  '1. 重构渲染引擎',
  '2. 支持蓝牙互联缓存图片'
])
assert.ok(!parsed.changelog.some((line) => line.includes('手机端') || line.includes('桌面端')))

// 3. 兼容 Vela端 / 数组 / 空日志
assert.deepEqual(
  extractVelaChangelog({ Vela端: ['1. 已带序号条目'] }),
  ['1. 已带序号条目']
)
assert.deepEqual(
  extractVelaChangelog({ 手机端: ['只有手机端'] }),
  ['常规稳定性修复与性能优化。']
)

// 4. 真实 hydromarket-index/app.json 校验（若存在）
const realAppJsonPath = path.resolve(__dirname, '../../../hydromarket-index/app.json')
if (fs.existsSync(realAppJsonPath)) {
  const realParsed = parseUpdateInfo(fs.readFileSync(realAppJsonPath, 'utf8'))
  assert.equal(realParsed.version, '1.3.0')
  assert.ok(realParsed.changelog.length >= 4)
  assert.ok(realParsed.downloadUrl.startsWith('https://'))
}

// 5. 版本比较与强制更新判定
assert.equal(compareVersions('1.3.0', '1.2.3'), 1)
assert.equal(compareVersions('1.3', '1.3.0'), 0)
assert.equal(compareVersions('1.3.0', '1.3.0-beta'), 1)
assert.equal(isNewer('1.3.0', '1.2.3'), true)
assert.equal(isNewer('1.3.0', '1.3.0'), false)
assert.equal(isForceUpdate({ force: true, minVersion: '' }, '1.3.0'), true)
assert.equal(isForceUpdate({ force: false, minVersion: '1.3.0' }, '1.2.3'), true)
assert.equal(isForceUpdate({ force: false, minVersion: '1.2.0' }, '1.2.3'), false)

// 6. 每日频控与忽略判定
assert.equal(shouldAutoCheck('', '2026-10-31'), true)
assert.equal(shouldAutoCheck('2026-10-30', '2026-10-31'), true)
assert.equal(shouldAutoCheck('2026-10-31', '2026-10-31'), false)
assert.equal(shouldPrompt('', '2026-10-31'), true)
assert.equal(shouldPrompt('2026-10-31', '2026-10-31'), false)

console.log('[test-update-helper] PASS')

// 生成端到端 golden：用**仓库里的 Rust WASM 切片器**（crates/hydro-media-wasm）
// 把一组确定性帧压成 GIF，作为安卓端 Kotlin 移植（com.hydro.link.server.media.*）
// 的逐字节对拍基准。
//
// 为什么要有它：切片能力现在内嵌在宿主里（Kotlin 实现），而参考实现的正确性
// 由 Rust 侧原有的测试保证。两边算出的 GIF 必须**逐字节一致**，否则"移植成功"
// 只是嘴上说说。Kotlin 测试读这里的产物 + 复刻同一组输入帧（整数算法，无浮点
// 歧义），逐字节比对。
//
// 重新生成（改了 Rust 编码器后必须跑）：
//   cd crates/hydro-media-wasm
//   cargo build --release --target wasm32-unknown-unknown
//   node test/make-golden.mjs
//
// 生成物落在 android/app/src/test/resources/media/（Gradle 的测试资源，改了会自动重跑测试）。

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMediaSlicer } from '../js/mediaSlicer.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '../../..');
const outDir = path.join(repoRoot, 'android/app/src/test/resources/media');

const wasmPath = path.resolve(__dirname, '../target/wasm32-unknown-unknown/release/hydro_media_wasm.wasm');
assert.ok(fs.existsSync(wasmPath), 'WASM 文件必须存在（先 cargo build --release --target wasm32-unknown-unknown）: ' + wasmPath);

// ---- 输入帧生成：整数算法，Kotlin 测试里逐行复刻（不许用浮点，两端才对得上） ----

/** gradient：平滑渐变 + 每帧整体偏移；variance 让不同帧的 b 通道错开。 */
function fillGradient(buf, w, h, f, opts) {
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      buf[i] = ((x * 255) / (w - 1)) | 0;
      buf[i + 1] = ((y * 255) / (h - 1)) | 0;
      buf[i + 2] = (((x >> 2) + (y >> 2)) * 2 + f * 13) & 0xFF;
      buf[i + 3] = 255;
    }
  }
  // 透明度：只有显式要求才做（视频解码出来的帧是不透明的；这条路径是给
  // 带 alpha 的输入留的，单独用一个小 case 钉住）
  if (opts.transparentMod) {
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        if ((x * 3 + y * 5 + f) % opts.transparentMod === 0) buf[(y * w + x) * 4 + 3] = 0;
      }
    }
  }
}

/** noise：接近随机的帧，用来把 LZW 字典撑满（覆盖 12 位码长增长与 Clear 重置路径）。 */
function fillNoise(buf, w, h, f) {
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      buf[i] = (x * 7 + f * 41) & 0xFF;
      buf[i + 1] = (y * 13 + f * 29) & 0xFF;
      buf[i + 2] = (x * y + f * 97) & 0xFF;
      buf[i + 3] = 255;
    }
  }
}

function buildFrames(c) {
  const out = [];
  for (let f = 0; f < c.frames; f++) {
    const buf = new Uint8Array(c.srcWidth * c.srcHeight * 4);
    if (c.pattern === 'gradient') fillGradient(buf, c.srcWidth, c.srcHeight, f, c);
    else if (c.pattern === 'noise') fillNoise(buf, c.srcWidth, c.srcHeight, f);
    else throw new Error('未知 pattern: ' + c.pattern);
    out.push(buf);
  }
  return out;
}

// ---- golden 用例表（Kotlin 测试读 golden-spec.json，自动覆盖这里的每一项） ----

const cases = [
  {
    name: 'golden-small',
    note: '小尺寸 + 3 帧：覆盖逐帧不同、局部调色板、含 alpha 帧的透明索引路径',
    srcWidth: 64, srcHeight: 36, dstWidth: 32, dstHeight: 18,
    frames: 3, fps: 5, pattern: 'gradient', transparentMod: 41,
  },
  {
    name: 'golden-video',
    note: '240x135（像素数 >16384 → 采样步长 4）：手表默认档位',
    srcWidth: 480, srcHeight: 270, dstWidth: 240, dstHeight: 135,
    frames: 3, fps: 5, pattern: 'gradient',
  },
  {
    name: 'golden-noise',
    note: '不缩放 + 噪声：覆盖 256 色全用满、LZW 12 位码长增长与字典满后的 Clear 重置',
    srcWidth: 128, srcHeight: 64, dstWidth: 128, dstHeight: 64,
    frames: 2, fps: 10, pattern: 'noise',
  },
];

const wasmBuffer = fs.readFileSync(wasmPath);
const slicer = await createMediaSlicer(wasmBuffer);
fs.mkdirSync(outDir, { recursive: true });

const spec = [];
let total = 0;
for (const c of cases) {
  const chunks = buildFrames(c);
  const joined = new Uint8Array(chunks.reduce((n, b) => n + b.length, 0));
  let off = 0;
  for (const b of chunks) { joined.set(b, off); off += b.length; }

  const gif = slicer.encodeFramesToGif(joined, {
    srcWidth: c.srcWidth, srcHeight: c.srcHeight,
    dstWidth: c.dstWidth, dstHeight: c.dstHeight,
    frameCount: c.frames, fps: c.fps,
  });

  // 自检：产物必须是真的 GIF，且帧数对得上（否则 golden 本身是垃圾）
  assert.ok(gif.length > 32, c.name + ': GIF 太小');
  assert.equal(String.fromCharCode(...gif.slice(0, 6)), 'GIF89a', c.name + ': 缺 GIF89a 头');
  let gce = 0;
  for (let i = 0; i + 1 < gif.length; i++) if (gif[i] === 0x21 && gif[i + 1] === 0xF9) gce++;
  assert.equal(gce, c.frames, c.name + ': 帧数不匹配（期望 ' + c.frames + '，实得 ' + gce + '）');
  assert.equal(gif[gif.length - 1], 0x3B, c.name + ': 缺 GIF 结尾 0x3B');

  fs.writeFileSync(path.join(outDir, c.name + '.gif'), gif);
  total += gif.length;
  const { note, ...rest } = c;
  spec.push({ ...rest, note, bytes: gif.length });
  console.log(`✓ ${c.name}.gif  ${gif.length} B  ${c.dstWidth}x${c.dstHeight} × ${c.frames} 帧`);
}

// golden 之间必须互不相同（否则是同一张图在骗自己）
const blobs = cases.map(c => fs.readFileSync(path.join(outDir, c.name + '.gif')).toString('base64'));
assert.equal(new Set(blobs).size, blobs.length, 'golden 用例之间应当互不相同');

fs.writeFileSync(path.join(outDir, 'golden-spec.json'), JSON.stringify(spec, null, 2) + '\n');
console.log(`\n共 ${cases.length} 个 golden，合计 ${(total / 1024).toFixed(1)} KB → ${path.relative(repoRoot, outDir)}`);

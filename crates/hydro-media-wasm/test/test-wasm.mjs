import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMediaSlicer } from '../js/mediaSlicer.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

console.log('--- 测试: Rust WASM 视频切片压制器 端到端集成 ---');

const wasmPath = path.resolve(__dirname, '../target/wasm32-unknown-unknown/release/hydro_media_wasm.wasm');
assert.ok(fs.existsSync(wasmPath), 'WASM 文件必须存在: ' + wasmPath);

const wasmBuffer = fs.readFileSync(wasmPath);
console.log(`载入 WASM 文件成功，体积: ${(wasmBuffer.length / 1024).toFixed(2)} KB`);

const slicer = await createMediaSlicer(wasmBuffer);
assert.ok(slicer, '成功实例化 WASM 模块');

// 1. 生成模拟的原分辨率视频帧 (480x270, 2 个切片，每个切片 3 帧)
const srcW = 480;
const srcH = 270;
const dstW = 240;
const dstH = 135;
const framesPerChunk = 3;
const fps = 10;

function createSimulatedChunk(chunkIndex) {
  const bytes = new Uint8Array(srcW * srcH * 4 * framesPerChunk);
  let ptr = 0;
  for (let f = 0; f < framesPerChunk; f++) {
    const shift = (chunkIndex * 3 + f) * 40;
    for (let y = 0; y < srcH; y++) {
      for (let x = 0; x < srcW; x++) {
        bytes[ptr++] = (x + shift) % 256;         // R
        bytes[ptr++] = (y + shift * 2) % 256;     // G
        bytes[ptr++] = (shift * 3) % 256;         // B
        bytes[ptr++] = 255;                       // A
      }
    }
  }
  return bytes;
}

const chunk1 = createSimulatedChunk(0);
const chunk2 = createSimulatedChunk(1);

// 2. 切片压制调用
const segments = slicer.generateSegments([chunk1, chunk2], {
  srcWidth: srcW,
  srcHeight: srcH,
  dstWidth: dstW,
  dstHeight: dstH,
  framesPerChunk,
  fps
});

assert.equal(segments.length, 2, '必须产出 2 个分段');

// 3. 校验分段数据格式与 GIF 二进制有效性
for (let i = 0; i < segments.length; i++) {
  const seg = segments[i];
  console.log(`分段 ${i + 1}: duration=${seg.duration}ms, bytes=${seg.bytes}B`);

  assert.ok(seg.src.startsWith('data:image/gif;base64,'), '分段 src 必须是合法 base64 GIF 数据');
  assert.equal(seg.duration, 300, '3 帧在 10 fps 下时长应为 300ms');

  const rawBase64 = seg.src.replace('data:image/gif;base64,', '');
  const gifBuf = Buffer.from(rawBase64, 'base64');
  assert.equal(gifBuf.length, seg.bytes);

  // 校验 GIF 规范头
  assert.equal(gifBuf.subarray(0, 6).toString('ascii'), 'GIF89a', '文件头必须为 GIF89a');

  // 校验尺寸 (240x135)
  const width = gifBuf.readUInt16LE(6);
  const height = gifBuf.readUInt16LE(8);
  assert.equal(width, dstW, `宽度应缩放为 ${dstW}`);
  assert.equal(height, dstH, `高度应缩放为 ${dstH}`);

  // 校验文件尾 0x3B
  assert.equal(gifBuf[gifBuf.length - 1], 0x3B, '文件尾必须是 0x3B');
}

console.log('✓ Rust WASM 视频切片压制器全部端到端验证通过！');

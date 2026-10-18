// 桌搭插件回归（node --test）：出树 + **模糊背景必须以 <image> 节点铺底**。
//
// 为什么盯这一条：背景曾经是 CSS `background-image`，在手表上是"静默失效"——
// 同一时刻封面 <image> 能出、背景出不来（Vela 对远程 URL 的 CSS 背景图支持不可靠，
// 文档里的背景图示例也全是本地资源）。所以这里的判据不是"样式里写了背景"，
// 而是"背景真的落进了渲染树、且铺满整屏、且在最底层"——拿真排版引擎跑出来的结果说话。
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { compileHydroUi } from '../../../vela/src/pages/hydro/layout/uiLayout.js';

const here = path.dirname(fileURLToPath(import.meta.url));

const MUSIC = {
  hasMedia: true,
  title: 'Perfect Strangers',
  artist: 'Jonas Blue/JP Cooper',
  playing: true,
  artUrl: 'http://192.168.3.48:9000/api/music/art',
  artBlur: 'http://192.168.3.48:9000/api/music/art?size=466&blur=26&dim=0.3',
};

function makeKit({ music = MUSIC, store = {} } = {}) {
  return {
    music: { now: async () => music, action: async () => ({ ok: true }) },
    storage: {
      getItem: async (k) => (k in store ? store[k] : null),
      setItem: async (k, v) => { store[k] = v; },
    },
    notify: async () => ({ ok: true }),
    fetch: async () => ({ ok: false, status: 599, json: async () => ({}), text: async () => '' }),
  };
}

function loadPlugin(kit) {
  const src = fs.readFileSync(path.join(here, 'server.js'), 'utf8');
  const mod = { exports: {} };
  const sandbox = {
    module: mod, exports: mod.exports, kit,
    console, Math, Date, JSON, Object, Array, String, Number, isNaN, encodeURIComponent,
    // 插件里有 SPlayer 心跳定时器与 WebSocket；单测里只需要它们不炸
    setInterval: () => 0, clearInterval: () => {}, setTimeout: () => 0, clearTimeout: () => {},
    WebSocket: function () { this.send = () => {}; this.close = () => {}; },
    fetch: async () => ({ ok: false, status: 599, json: async () => ({}), text: async () => '' }),
    isFinite, parseFloat, parseInt, Buffer,
    globalThis: {},
  };
  sandbox.globalThis = sandbox;
  vm.runInNewContext(src, sandbox);
  return mod.exports;
}

function nodesOf(view, shape) {
  return compileHydroUi(view.root, { shape, width: 466, height: 466, dpr: 2 }).flatNodes;
}

test('模糊背景以 <image> 节点铺底，且铺满整屏（不是 CSS background-image）', async () => {
  const view = await loadPlugin(makeKit()).view('app', 'main', {});
  assert.ok(view && view.root, '插件应当出树');
  assert.equal(view.root.style.backgroundImage, undefined, 'rootStyle 不应再带 backgroundImage（那条路渲染不可靠）');

  for (const shape of ['circle', 'rect']) {
    const nodes = nodesOf(view, shape);
    const cssBg = nodes.filter((n) => /background-image/.test(n.wrapStyle || ''));
    assert.equal(cssBg.length, 0, `${shape}: 不该有节点用 CSS background-image`);

    const bg = nodes.find((n) => n.kind === 'image' && /blur=/.test(n.src || ''));
    assert.ok(bg, `${shape}: 背景必须作为 image 节点进渲染树`);
    assert.ok(bg.x <= 0 && bg.y <= 0, `${shape}: 背景应从屏幕外铺进来（x=${bg.x} y=${bg.y}）`);
    assert.ok(bg.w >= 480 && bg.h >= 480, `${shape}: 背景应铺满 480 基准屏（实际 ${bg.w}x${bg.h}）`);
    assert.equal(nodes[0], bg, `${shape}: 背景必须排在最底层`);
  }
});

test('封面仍以 <image> 节点下发（可点击切歌）', async () => {
  const view = await loadPlugin(makeKit()).view('app', 'main', {});
  const nodes = nodesOf(view, 'circle');
  const cover = nodes.find((n) => n.kind === 'image' && /size=466/.test(n.src || '') && !/blur=/.test(n.src || ''));
  assert.ok(cover, '封面 image 节点应在');
  assert.equal(cover.tap, 'toggle', '点封面应触发 toggle');
});

test('没有媒体时不下发背景与封面（退回纯色）', async () => {
  const music = { hasMedia: false, title: '', artist: '', playing: false, artUrl: '', artBlur: '' };
  const view = await loadPlugin(makeKit({ music })).view('app', 'main', {});
  const nodes = nodesOf(view, 'circle');
  assert.equal(nodes.filter((n) => n.kind === 'image').length, 0, '无媒体时不该有任何图片节点');
});

test('歌词版式同样铺背景', async () => {
  const view = await loadPlugin(makeKit({ store: { splayerLyrics: true } })).view('app', 'main', {});
  const nodes = nodesOf(view, 'circle');
  const bg = nodes.find((n) => n.kind === 'image' && /blur=/.test(n.src || ''));
  assert.ok(bg, '歌词版式也要铺背景');
  assert.equal(nodes[0], bg, '背景必须在最底层');
});

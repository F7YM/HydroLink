const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function harness() {
  const state = { auth: null, requests: [], replies: [] };
  const context = vm.createContext({
    module: { exports: {} }, console, setTimeout, clearTimeout, AbortController,
    kit: {
      storage: { async getItem(key) {
        assert.equal(key, 'bili_auth');
        return state.auth;
      } },
      async loadWasm(assetPath) {
        const wasmBuf = fs.readFileSync(path.join(__dirname, assetPath));
        const mod = await WebAssembly.instantiate(wasmBuf);
        return { exports: mod.instance.exports };
      }
    },
    async fetch(url, options) {
      state.requests.push({ url, options });
      assert.ok(state.replies.length, 'Unexpected network request');
      const reply = state.replies.shift();
      return { ok: true, status: 200, async json() { return reply; } };
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), context);
  return { state, context, plugin: context.module.exports };
}

const nav = { code: 0, data: { isLogin: true, wbi_img: {
  img_url: 'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png',
  sub_url: 'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png'
} } };
const video = { goto: 'av', bvid: 'BV1xx411c7m9', title: '测试视频',
  owner: { name: '测试作者' }, pic: '//i0.hdslb.com/bfs/archive/example.jpg',
  duration: 125, stat: { view: 12345 } };
const auth = { uid: '123', sessdata: 'test-session-only', bili_jct: 'test-csrf-only' };

test('MD5 matches independent implementation, including UTF-8 and block boundaries', () => {
  const { context } = harness();
  for (const value of ['', 'abc', '中文推荐', ...[55, 56, 63, 64, 65, 120].map(n => 'a'.repeat(n))]) {
    assert.equal(context.md5(value), crypto.createHash('md5').update(value).digest('hex'));
  }
});

test('WBI matches documented vector', () => {
  const { context } = harness();
  assert.equal(context.signWbi({ foo: '114', bar: '514', zab: 1919810 },
    '7cd084941338484aae1ad9425b84077c', '4932caff0ff746eab6f01bf08b70ac45', 1702204169),
  'bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4');
});

test('only HydroApp main is exposed; logged-out view makes no external requests', async () => {
  const { state, plugin } = harness();
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, 'manifest.json'), 'utf8'));
  assert.equal(manifest.apps.length, 1);
  assert.equal(manifest.apps[0].name, 'HydroBili');
  assert.equal(manifest.widgets, undefined);
  assert.equal(plugin.manifest().widgets, undefined);
  assert.equal(await plugin.view('widget', 'main', {}), null);
  assert.equal(await plugin.view('app', 'unknown', {}), null);
  assert.match(JSON.stringify(await plugin.view('app', 'main', {})), /尚未登录/);
  assert.equal(state.requests.length, 0);
});

test('homepage recommendation filters other surfaces and duplicates, caches, and refreshes', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  state.replies.push(nav, { code: 0, data: { item: [video, video,
    { ...video, goto: 'live', bvid: 'BVlive' },
    { ...video, bvid: 'BVadvert', business_info: { archive: {} } }
  ] } });
  const view = await plugin.view('app', 'main', {});
  const serialized = JSON.stringify(view);
  assert.equal((serialized.match(/测试视频/g) || []).length, 1);
  assert.match(serialized, /测试作者/);
  assert.match(serialized, /1.2万播放 · 2:05/);
  assert.match(serialized, /160w_90h_1c_70q.jpg/);
  assert.ok(!serialized.includes(auth.sessdata));
  assert.ok(!serialized.includes('查看详情'), '卡片中不应存在“查看详情”多余文字');
  assert.match(serialized, /open_video:BV1xx411c7m9/, '卡片必须包含 open_video 点击事件');
  assert.equal(view.storage, undefined);
  assert.equal(view.refreshMs, 0);
  assert.equal(state.requests.length, 2);
  const request = new URL(state.requests[1].url);
  assert.equal(request.pathname, '/x/web-interface/wbi/index/top/feed/rcmd');
  assert.match(request.searchParams.get('w_rid'), /^[a-f0-9]{32}$/);
  assert.match(state.requests[1].options.headers.Cookie, /SESSDATA=test-session-only/);
  await plugin.view('app', 'main', {});
  assert.equal(state.requests.length, 2);
  state.replies.push({ code: 0, data: { item: [] } });
  const empty = await plugin.view('app', 'main', { trigger: 'event', event: { handler: 'refresh' } });
  assert.match(JSON.stringify(empty), /本批暂无视频推荐/);
  assert.equal(new URL(state.requests[2].url).searchParams.get('fresh_idx'), '2');
});

test('switching accounts invalidates recommendations and signing cache', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  state.replies.push(nav, { code: 0, data: { item: [video] } });
  await plugin.view('app', 'main', {});
  state.auth = { ...auth, uid: '456', sessdata: 'second-test-session' };
  state.replies.push(nav, { code: 0, data: { item: [] } });
  const view = await plugin.view('app', 'main', {});
  assert.equal(state.requests.length, 4);
  assert.ok(!JSON.stringify(view).includes('测试视频'));
  assert.match(state.requests[3].options.headers.Cookie, /second-test-session/);
});

test('oversized API batches stay bounded on the watch and refresh replaces the batch', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  state.replies.push(nav, { code: 0, data: { item: Array.from({ length: 12 }, (_, i) => ({
    ...video, bvid: 'BVtest' + i, title: '首批视频' + i
  })) } });
  function nodes(root) {
    return [root, ...(root.children || []).flatMap(nodes)];
  }
  const first = await plugin.view('app', 'main', {});
  const firstNodes = nodes(first.root);
  assert.equal(firstNodes.filter(n => n.type === 'image').length, 4);
  assert.equal(firstNodes.filter(n => n.type === 'text' && n.props.text.startsWith('首批视频')).length, 4);
  assert.equal(new URL(state.requests[1].url).searchParams.get('ps'), '4');
  assert.ok(!firstNodes.some(n => n.type === 'nav' && n.props.action === 'back'));
  assert.ok(first.fab && first.fab.tap === 'refresh');
  state.replies.push({ code: 0, data: { item: [{ ...video, bvid: 'BVnext', title: '下一批视频' }] } });
  const next = await plugin.view('app', 'main', {
    trigger: 'event', event: { handler: first.fab.tap, source: 'tap' }
  });
  assert.match(JSON.stringify(next), /下一批视频/);
  assert.ok(!JSON.stringify(next).includes('首批视频'));
  assert.equal(nodes(next.root).filter(n => n.type === 'image').length, 1);
  assert.equal(new URL(state.requests[2].url).searchParams.get('fresh_idx'), '2');
});

test('expired login and verification responses produce actionable errors', async () => {
  for (const reply of [{ code: -101 }, { code: 0, data: { v_voucher: 'test-voucher' } }]) {
    const { state, plugin } = harness();
    state.auth = auth;
    state.replies.push(reply);
    const result = JSON.stringify(await plugin.view('app', 'main', {}));
    assert.match(result, /暂时无法加载/);
    assert.match(result, reply.code === -101 ? /登录已失效/ : /暂时限制/);
    assert.ok(!result.includes('test-session-only'));
    assert.ok(!result.includes('test-voucher'));
  }
});

test('packaged slice.wasm can be loaded and slices RGBA frames to GIF', async () => {
  const { context } = harness();
  assert.ok(typeof context.getSlicer === 'function', 'getSlicer must be defined in server.js');
  const slicer = await context.getSlicer();
  assert.ok(slicer, 'slice.wasm must load successfully through kit.loadWasm');
  assert.ok(typeof slicer.slice_video_frames_to_gif === 'function', 'WASM module must export slice_video_frames_to_gif');

  // 测试单帧快速压制
  const w = 40, h = 40;
  const frameBytes = new Uint8Array(w * h * 4);
  frameBytes.fill(128);
  const gif = await context.sliceFramesToGif(frameBytes, {
    srcWidth: w, srcHeight: h,
    dstWidth: 20, dstHeight: 20,
    frameCount: 1, fps: 10
  });

  assert.ok(gif && gif.length > 50, 'GIF 压缩数据必须存在且大于 50B');
  assert.equal(Buffer.from(gif.slice(0, 6)).toString('ascii'), 'GIF89a', '切片结果必须是合法的 GIF89a');
});

test('video detail view displays stats, desc, and provides play video event', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  // 模拟 view 接口返回
  const detailReply = {
    code: 0,
    data: {
      bvid: 'BVtestDetail',
      aid: 1234567,
      cid: 998877,
      title: '详细测试视频标题',
      desc: '这是一个详尽的测试简介。',
      pic: 'https://i0.hdslb.com/bfs/archive/detail_cover.jpg',
      owner: { name: '高能UP主', face: 'https://i0.hdslb.com/face.jpg' },
      stat: { view: 88888, danmaku: 666, like: 9999, coin: 520, favorite: 1314 },
      duration: 185
    }
  };
  state.replies.push(detailReply);

  const detailView = await plugin.view('app', 'main', {
    trigger: 'event',
    event: { handler: 'open_video:BVtestDetail', source: 'tap' }
  });

  assert.equal(detailView.title, '视频详情');
  const serialized = JSON.stringify(detailView);
  assert.match(serialized, /详细测试视频标题/);
  assert.match(serialized, /高能UP主/);
  assert.match(serialized, /8.9万/);
  assert.match(serialized, /这是一个详尽的测试简介/);
  assert.match(serialized, /play_video:BVtestDetail:998877/);
  assert.match(serialized, /back_to_feed/);
  assert.equal(state.requests.length, 1);
  const detailReqUrl = new URL(state.requests[0].url);
  assert.equal(detailReqUrl.pathname, '/x/web-interface/view');
  assert.equal(detailReqUrl.searchParams.get('bvid'), 'BVtestDetail');
});

test('play_video fetches WBI-signed playurl and returns native video component', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  // 1. view 详情响应
  state.replies.push({
    code: 0,
    data: {
      bvid: 'BVtestPlay',
      cid: 112233,
      title: '播放测试视频',
      desc: '简介内容',
      pic: 'https://i0.hdslb.com/cover.jpg',
      owner: { name: 'UP主' },
      stat: { view: 100 },
      duration: 60
    }
  });
  // 2. nav WBI keys 响应
  state.replies.push(nav);
  // 3. playurl 播放地址响应
  state.replies.push({
    code: 0,
    data: {
      quality: 16,
      format: 'mp4',
      durl: [
        {
          order: 1,
          length: 60000,
          size: 1048576,
          url: 'https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/test_360p.mp4'
        }
      ]
    }
  });

  const playView = await plugin.view('app', 'main', {
    trigger: 'event',
    event: { handler: 'play_video:BVtestPlay:112233', source: 'tap' }
  });

  assert.equal(playView.title, '视频详情');
  function findNodes(root, predicate) {
    const matched = [];
    if (predicate(root)) matched.push(root);
    for (const child of root.children || []) {
      matched.push(...findNodes(child, predicate));
    }
    return matched;
  }

  const videoNodes = findNodes(playView.root, n => n.type === 'video');
  assert.equal(videoNodes.length, 1, '必须包含一个 video 原生组件');
  const v = videoNodes[0];
  assert.equal(v.props.src, 'https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/test_360p.mp4');
  assert.equal(v.props.autoplay, true);
  assert.equal(v.props.controls, true);

  // 验证调用了正确的 playurl 接口且带了 WBI 签名
  assert.equal(state.requests.length, 3);
  const playReqUrl = new URL(state.requests[2].url);
  assert.equal(playReqUrl.pathname, '/x/player/wbi/playurl');
  assert.equal(playReqUrl.searchParams.get('bvid'), 'BVtestPlay');
  assert.equal(playReqUrl.searchParams.get('cid'), '112233');
  assert.equal(playReqUrl.searchParams.get('qn'), '16');
  assert.equal(playReqUrl.searchParams.get('fnval'), '0');
  assert.match(playReqUrl.searchParams.get('w_rid'), /^[a-f0-9]{32}$/);
});

test('back_to_feed event returns feed without forcing fresh network request', async () => {
  const { state, plugin } = harness();
  state.auth = auth;
  state.replies.push(nav, { code: 0, data: { item: [video] } });
  // 1. 首次打开推荐
  const feed1 = await plugin.view('app', 'main', {});
  assert.equal(state.requests.length, 2);
  assert.equal(feed1.title, '首页推荐');
  assert.match(JSON.stringify(feed1), /测试视频/);

  // 2. 模拟从详情页点击“‹ 返回推荐”
  const backView = await plugin.view('app', 'main', {
    trigger: 'event',
    event: { handler: 'back_to_feed', source: 'tap' }
  });
  assert.equal(backView.title, '首页推荐');
  assert.match(JSON.stringify(backView), /测试视频/);
  // 不应触发强制刷新外部请求，请求数仍为 2
  assert.equal(state.requests.length, 2, 'back_to_feed 应复用缓存，不应触发多余的外部网络请求');
});

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
    kit: { storage: { async getItem(key) {
      assert.equal(key, 'bili_auth');
      return state.auth;
    } } },
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
  assert.match(serialized, /240w_135h_1c.jpg/);
  assert.ok(!serialized.includes(auth.sessdata));
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
  const refresh = firstNodes.find(n => n.type === 'button' && n.props.label === '换一批');
  assert.ok(refresh);
  state.replies.push({ code: 0, data: { item: [{ ...video, bvid: 'BVnext', title: '下一批视频' }] } });
  const next = await plugin.view('app', 'main', {
    trigger: 'event', event: { handler: refresh.events.tap, source: 'tap' }
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

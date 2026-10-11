const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function harness(saved = null) {
  const elements = new Map();
  const timers = new Map();
  const requests = [];
  const replies = [{ found: !!saved, value: saved }];
  const listeners = {};
  const qrCodes = [];
  let nextTimer = 0;
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      hidden: false, disabled: false, textContent: '', handlers: {},
      addEventListener(name, callback) { this.handlers[name] = callback; },
      replaceChildren() {}, removeAttribute(name) { delete this[name]; }
    });
    return elements.get(id);
  }
  function QRCode(container, options) { qrCodes.push(options.text); }
  QRCode.CorrectLevel = { M: 0 };
  const context = vm.createContext({
    document: { getElementById: element },
    window: { addEventListener(name, callback) { listeners[name] = callback; } },
    URL, URLSearchParams, AbortController, QRCode, console, confirm: () => true,
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    async fetch(url, options) {
      requests.push({ url, options });
      assert.ok(replies.length, 'Unexpected request: ' + url);
      let reply = replies.shift();
      if (typeof reply === 'function') reply = await reply();
      return { ok: !reply.httpError, status: reply.httpError || 200,
        headers: { get: () => reply.cookies || '' }, async json() { return reply.body || reply; } };
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'login.js'), 'utf8'), context);
  async function settle() { for (let i = 0; i < 25; i++) await Promise.resolve(); }
  async function click(id) { await element(id).handlers.click(); await settle(); }
  async function poll() {
    const entry = [...timers].find(([, value]) => value.delay === 2000);
    assert.ok(entry, 'Expected scheduled QR poll');
    timers.delete(entry[0]);
    entry[1].callback();
    await settle();
  }
  async function generate() {
    replies.push({ code: 0, data: { qrcode_key: 'fixture-key', url: 'https://passport.bilibili.com/fixture' } });
    await click('generate');
  }
  return { element, requests, replies, listeners, qrCodes, timers, settle, click, poll, generate };
}

const validUser = { code: 0, data: { isLogin: true, mid: 123, uname: '测试账号', face: 'https://i0.hdslb.com/face.jpg' } };
const confirmation = { code: 0, data: { code: 0, sessdata: 'fixture-session', bili_jct: 'fixture-csrf', DedeUserID: '123' } };

test('QR login waits for confirmation, verifies account, then saves server-side credentials', async () => {
  const h = harness();
  await h.settle();
  await h.generate();
  assert.equal(h.qrCodes.length, 1);
  for (const code of [86101, 86090]) {
    h.replies.push({ code: 0, data: { code } });
    await h.poll();
    assert.match(h.element('status').textContent, code === 86101 ? /等待扫描/ : /手机上确认/);
  }
  h.replies.push(confirmation, validUser, { ok: true });
  await h.poll();
  assert.equal(h.element('accountCard').hidden, false);
  assert.equal(h.element('name').textContent, '测试账号');
  const saved = h.requests.filter(r => r.options.method === 'POST').map(r => JSON.parse(r.options.body)).find(r => r.op === 'set');
  assert.equal(saved.package, 'com.hydro.bili');
  assert.equal(saved.scope, 'server');
  assert.equal(saved.value.sessdata, 'fixture-session');
  assert.equal(h.timers.size, 0);
  for (const id of ['notice', 'status', 'name', 'uid']) assert.ok(!h.element(id).textContent.includes('fixture-session'));
});

test('expired QR stops polling without writing credentials', async () => {
  const h = harness();
  await h.settle();
  await h.generate();
  h.replies.push({ code: 0, data: { code: 86038 } });
  await h.poll();
  assert.match(h.element('status').textContent, /已失效/);
  assert.equal(h.timers.size, 0);
  assert.ok(!h.requests.some(r => r.options.body && JSON.parse(r.options.body).op === 'set'));
});

test('confirmation without credentials never reports successful login', async () => {
  const h = harness();
  await h.settle();
  await h.generate();
  h.replies.push({ code: 0, data: { code: 0, url: 'https://passport.bilibili.com/fixture' } });
  await h.poll();
  assert.match(h.element('notice').textContent, /没有取得完整登录凭据/);
  assert.equal(h.timers.size, 0);
  assert.ok(!h.requests.some(r => r.options.body && JSON.parse(r.options.body).op === 'set'));
});

test('storage failure is surfaced instead of displaying login success', async () => {
  const h = harness();
  await h.settle();
  await h.generate();
  h.replies.push(confirmation, validUser, { status: 'error' });
  await h.poll();
  assert.match(h.element('notice').textContent, /无法读写/);
  assert.ok(!h.element('notice').textContent.includes('登录成功'));
  assert.equal(h.element('generate').disabled, false);
});

test('leaving WebUI ignores an in-flight QR response and does not restart polling', async () => {
  const h = harness();
  await h.settle();
  let resolve;
  h.replies.push(() => new Promise(done => { resolve = done; }));
  const pending = h.click('generate');
  await h.settle();
  h.listeners.pagehide();
  resolve({ code: 0, data: { qrcode_key: 'late', url: 'https://passport.bilibili.com/late' } });
  await pending;
  assert.equal(h.qrCodes.length, 0);
  assert.equal(h.timers.size, 0);
});

test('logout removes only HydroBili authentication from server storage', async () => {
  const h = harness({ sessdata: 'fixture-session', bili_jct: 'fixture-csrf', uid: '123' });
  h.replies.push(validUser);
  await h.settle();
  h.replies.push({ ok: true });
  await h.click('logout');
  const request = JSON.parse(h.requests.at(-1).options.body);
  assert.equal(request.op, 'remove');
  assert.equal(request.key, 'bili_auth');
  assert.equal(request.package, 'com.hydro.bili');
  assert.equal(request.scope, 'server');
  assert.equal(h.element('accountCard').hidden, true);
  assert.match(h.element('status').textContent, /已退出/);
});

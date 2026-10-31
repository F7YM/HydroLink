'use strict';
(function () {
  var PKG = 'com.hydro.bili';
  var AUTH_KEY = 'bili_auth';
  var epoch = 0;
  var timer = null;
  var pendingControllers = [];
  var qrKey = '';
  var qrDeadline = 0;
  var storedAuth = null;
  var byId = function (id) { return document.getElementById(id); };

  function notice(message) {
    byId('notice').textContent = message || '';
    byId('notice').hidden = !message;
  }

  function stop() {
    epoch++;
    clearTimeout(timer);
    timer = null;
    qrKey = '';
    pendingControllers.forEach(function (controller) { controller.abort(); });
    pendingControllers = [];
    return epoch;
  }

  async function jsonRequest(url, options) {
    var controller = new AbortController();
    pendingControllers.push(controller);
    var timeout = setTimeout(function () { controller.abort(); }, 12000);
    try {
      var response = await fetch(url, Object.assign({}, options, { signal: controller.signal, cache: 'no-store' }));
      if (!response.ok) {
        if (response.status === 404 && url.indexOf('/api/hydro/proxy?') === 0) {
          throw new Error('当前服务端缺少登录所需的 HTTP 代理能力。请使用支持该能力的 HydroLink 桌面 Compose 服务端。');
        }
        throw new Error('请求失败（HTTP ' + response.status + '），请检查服务端后重试。');
      }
      var data;
      try { data = await response.json(); }
      catch (_) { throw new Error('服务器无响应'); }
      return { data: data, cookies: response.headers.get('x-proxy-set-cookie') || '' };
    } finally {
      clearTimeout(timeout);
      pendingControllers = pendingControllers.filter(function (entry) { return entry !== controller; });
    }
  }

  async function storage(op, value) {
    var result = await jsonRequest('/api/hydro/worker/store', {
      method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ package: PKG, scope: 'server', op: op, key: AUTH_KEY, value: value })
    });
    if (!result.data || result.data.error || result.data.status === 'error') throw new Error('无法读写插件登录信息，请检查服务端与存储权限。');
    return result.data;
  }

  function bili(path, auth) {
    var headers = { 'X-Proxy-Referer': 'https://www.bilibili.com/' };
    if (auth) headers['X-Proxy-Cookie'] = 'SESSDATA=' + auth.sessdata + '; bili_jct=' + (auth.bili_jct || '') + '; DedeUserID=' + (auth.uid || '');
    return jsonRequest('/api/hydro/proxy?url=' + encodeURIComponent(path), { headers: headers });
  }

  function showAccount(auth) {
    storedAuth = auth;
    byId('accountCard').hidden = !auth;
    byId('loginCard').hidden = !!auth;
    byId('generate').disabled = false;
    if (!auth) return;
    byId('name').textContent = auth.name || '已登录用户';
    byId('uid').textContent = 'UID: ' + (auth.uid || '未知');
    var face = String(auth.face || '').replace(/^http:/, 'https:');
    byId('avatar').hidden = !/^https:\/\/(?:[a-z0-9-]+\.)*hdslb\.com\//i.test(face);
    if (!byId('avatar').hidden) byId('avatar').src = face;
    else byId('avatar').removeAttribute('src');
  }

  async function validate(auth) {
    var result = (await bili('https://api.bilibili.com/x/web-interface/nav', auth)).data;
    if (result && (result.code === -101 || (result.code === 0 && result.data && result.data.isLogin === false))) {
      var expired = new Error('登录已失效，请重新扫码。');
      expired.expired = true;
      throw expired;
    }
    if (!result || result.code !== 0 || !result.data || !result.data.isLogin) throw new Error('暂时无法验证账号，请稍后重试。');
    return Object.assign({}, auth, { uid: String(result.data.mid), name: result.data.uname || '', face: result.data.face || '' });
  }

  function credentials(reply) {
    var data = reply.data.data || {};
    var cookies = {};
    reply.cookies.split(';').forEach(function (part) {
      var pos = part.indexOf('=');
      if (pos > 0) cookies[part.slice(0, pos).trim().toLowerCase()] = part.slice(pos + 1).trim();
    });
    var query;
    try { query = new URL(data.url).searchParams; } catch (_) { query = new URLSearchParams(); }
    var auth = {
      sessdata: data.sessdata || data.SESSDATA || cookies.sessdata || query.get('SESSDATA') || '',
      bili_jct: data.bili_jct || cookies.bili_jct || query.get('bili_jct') || '',
      uid: String(data.DedeUserID || data.dede_user_id || cookies.dedeuserid || query.get('DedeUserID') || ''),
      refresh_token: data.refresh_token || '', login_at: Date.now()
    };
    if (!auth.sessdata || !auth.bili_jct || !auth.uid || /[\r\n;]/.test(auth.sessdata + auth.bili_jct + auth.uid)) {
      throw new Error('扫码已确认，但没有取得完整登录凭据。请重新获取二维码。');
    }
    return auth;
  }

  function fail(error) {
    notice(error && error.name === 'AbortError' ? '请求超时，请检查网络后重试。' : error.message || '操作失败，请重试。');
  }

  async function poll(token) {
    if (token !== epoch || !qrKey) return;
    if (Date.now() >= qrDeadline) { byId('status').textContent = '二维码已失效，请重新获取。'; return; }
    try {
      var reply = await bili('https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=' + encodeURIComponent(qrKey));
      if (token !== epoch) return;
      var payload = reply.data;
      if (!payload || payload.code !== 0 || !payload.data) throw new Error('无法查询扫码状态，请重新获取二维码。');
      var code = payload.data.code;
      if (code === 0) {
        byId('status').textContent = '正在验证账号并保存登录信息…';
        byId('generate').disabled = true;
        var auth = await validate(credentials(reply));
        if (token !== epoch) return;
        await storage('set', auth);
        if (token !== epoch) return;
        stop();
        notice('登录成功。现在可以在手表上打开 HydroBili。');
        showAccount(auth);
        return;
      }
      if (code === 86038) { byId('status').textContent = '二维码已失效，请重新获取。'; return; }
      if (code !== 86090 && code !== 86101) throw new Error('扫码状态异常，请重新获取二维码。');
      notice('');
      byId('status').textContent = code === 86090 ? '已扫码，请在手机上确认登录。' : '等待扫描，请使用哔哩哔哩手机客户端。';
    } catch (error) {
      if (token !== epoch) return;
      fail(error);
      byId('generate').disabled = false;
      byId('status').textContent = '登录尚未完成，请重新获取二维码。';
      return;
    }
    if (token === epoch) timer = setTimeout(function () { poll(token); }, 2000);
  }

  async function generate() {
    var token = stop();
    notice('');
    byId('generate').disabled = true;
    byId('qr').hidden = true;
    byId('status').textContent = '正在获取登录二维码…';
    try {
      var payload = (await bili('https://passport.bilibili.com/x/passport-login/web/qrcode/generate')).data;
      if (token !== epoch) return;
      if (!payload || payload.code !== 0 || !payload.data || !payload.data.qrcode_key || !payload.data.url) throw new Error('获取二维码失败，请重试。');
      qrKey = payload.data.qrcode_key;
      qrDeadline = Date.now() + 180000;
      byId('qr').replaceChildren();
      new QRCode(byId('qr'), { text: payload.data.url, width: 200, height: 200, correctLevel: QRCode.CorrectLevel.M });
      byId('qr').hidden = false;
      byId('status').textContent = '等待扫描，请使用哔哩哔哩手机客户端。';
      byId('generate').textContent = '重新获取二维码';
      timer = setTimeout(function () { poll(token); }, 2000);
    } catch (error) {
      if (token === epoch) { fail(error); byId('status').textContent = '未能生成二维码。'; }
    } finally { if (token === epoch) byId('generate').disabled = false; }
  }

  async function check() {
    var token = stop();
    notice('');
    byId('check').disabled = true;
    byId('logout').disabled = true;
    byId('generate').disabled = true;
    try {
      var saved = await storage('get');
      if (token !== epoch) return;
      var auth = saved.found && saved.value && saved.value.sessdata ? saved.value : null;
      if (!auth) { showAccount(null); byId('status').textContent = '点击下方按钮获取登录二维码。'; return; }
      storedAuth = auth;
      var verified = await validate(auth);
      if (token !== epoch) return;
      showAccount(verified);
    } catch (error) {
      if (token !== epoch) return;
      showAccount(error.expired ? null : storedAuth);
      if (!storedAuth || error.expired) byId('status').textContent = '请获取二维码登录。';
      fail(error);
    } finally {
      if (token === epoch) {
        byId('check').disabled = false;
        byId('logout').disabled = false;
        byId('generate').disabled = false;
      }
    }
  }

  byId('generate').addEventListener('click', generate);
  byId('check').addEventListener('click', check);
  byId('logout').addEventListener('click', async function () {
    if (!confirm('清除当前服务端保存的 HydroBili 登录信息？')) return;
    var token = stop();
    byId('logout').disabled = true;
    byId('check').disabled = true;
    try {
      await storage('remove');
      if (token !== epoch) return;
      showAccount(null);
      byId('qr').hidden = true;
      byId('status').textContent = '已退出，可重新扫码登录。';
      notice('');
    } catch (error) { if (token === epoch) fail(error); }
    finally { byId('logout').disabled = false; byId('check').disabled = false; }
  });
  window.addEventListener('pagehide', stop);
  window.addEventListener('pageshow', function (event) { if (event.persisted) check(); });
  check();
})();

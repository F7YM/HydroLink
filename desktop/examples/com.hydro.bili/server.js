// HydroBili — WebUI 登录，HydroApp 首页推荐。凭据只读写 server-scope storage。
// API: https://sessionhu.github.io/bilibili-API-collect/docs/video/recommend.html
// WBI: https://sessionhu.github.io/bilibili-API-collect/docs/misc/sign/wbi.html
var PKG = 'com.hydro.bili';
var AUTH_KEY = 'bili_auth';
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
var feedCache = null;
var wbiCache = null;
// 手表同时持有整棵视图树和解码封面；每批限制数量，避免一次创建十二张卡片。
var FEED_LIMIT = 4;

// 宿主端 WASM 视频切片压制器缓存
var slicerCache = null;

async function getSlicer() {
  if (slicerCache) return slicerCache;
  if (!kit || typeof kit.loadWasm !== 'function') return null;
  try {
    var wasm = await kit.loadWasm('assets/slice.wasm');
    if (wasm && wasm.exports) {
      slicerCache = wasm.exports;
      return slicerCache;
    }
  } catch (err) {
    console.warn('[bili] 加载 slice.wasm 失败:', err);
  }
  return null;
}

async function sliceFramesToGif(rgbaBuffer, opts) {
  var slicer = await getSlicer();
  if (!slicer || !rgbaBuffer) return null;
  var mem = slicer.memory;
  var ptr = slicer.hydro_alloc(rgbaBuffer.length);
  if (!ptr) return null;
  new Uint8Array(mem.buffer, ptr, rgbaBuffer.length).set(rgbaBuffer);

  var outPtr = slicer.slice_video_frames_to_gif(
    ptr, rgbaBuffer.length,
    opts.srcWidth, opts.srcHeight,
    opts.dstWidth, opts.dstHeight,
    opts.frameCount, opts.fps || 10
  );

  slicer.hydro_free(ptr, rgbaBuffer.length);
  if (!outPtr) return null;

  var view = new DataView(mem.buffer);
  var len = view.getUint32(outPtr, true);
  var bytes = new Uint8Array(mem.buffer, outPtr + 4, len).slice();
  slicer.hydro_free(outPtr, 4 + len);
  return bytes;
}

// MD5 用于 WBI 请求签名；不依赖 Node 模块或宿主 crypto 扩展。
function md5(text) {
  var bytes = unescape(encodeURIComponent(text));
  var words = [];
  var i;
  for (i = 0; i < bytes.length; i++) words[i >> 2] = (words[i >> 2] || 0) | bytes.charCodeAt(i) << ((i % 4) * 8);
  words[i >> 2] = (words[i >> 2] || 0) | 128 << ((i % 4) * 8);
  var length = (((bytes.length + 8) >>> 6) + 1) * 16;
  words[length - 2] = bytes.length * 8;
  words[length - 1] = Math.floor(bytes.length / 536870912);
  var shifts = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
  var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (var offset = 0; offset < length; offset += 16) {
    var a = a0, b = b0, c = c0, d = d0;
    for (i = 0; i < 64; i++) {
      var f, g;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * i) % 16; }
      var sum = (a + f + (Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0) + (words[offset + g] || 0)) | 0;
      var shift = shifts[(i >>> 4) * 4 + i % 4];
      a = d; d = c; c = b;
      b = (b + ((sum << shift) | (sum >>> (32 - shift)))) | 0;
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0;
  }
  return [a0, b0, c0, d0].map(function (word) {
    var result = '';
    for (var j = 0; j < 4; j++) result += ('0' + ((word >>> (j * 8)) & 255).toString(16)).slice(-2);
    return result;
  }).join('');
}

function signWbi(params, imgKey, subKey, timestamp) {
  var table = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13];
  var raw = imgKey + subKey;
  var mixin = table.map(function (index) { return raw.charAt(index); }).join('');
  var values = Object.assign({}, params, { wts: timestamp });
  var query = Object.keys(values).sort().map(function (key) {
    return encodeURIComponent(key) + '=' + encodeURIComponent(String(values[key]).replace(/[!'()*]/g, ''));
  }).join('&');
  return query + '&w_rid=' + md5(query + mixin);
}

function safeText(value) {
  return String(value == null ? '' : value)
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
    .replace(/[\u0000-\u001F]/g, ' ').trim();
}

function cookie(auth) {
  return ['SESSDATA=' + auth.sessdata, 'bili_jct=' + (auth.bili_jct || ''), 'DedeUserID=' + (auth.uid || '')].join('; ');
}

async function requestJson(url, auth) {
  var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var timer;
  try {
    var operation = (async function () {
      var response = await fetch(url, {
        headers: { 'User-Agent': UA, Referer: 'https://www.bilibili.com/', Cookie: cookie(auth) },
        signal: controller ? controller.signal : undefined
      });
      if (!response.ok) throw new Error('请求失败（HTTP ' + response.status + '），请稍后重试');
      try { return await response.json(); }
      catch (error) { throw new Error('B站返回了无法识别的内容，请稍后重试'); }
    })();
    return await Promise.race([operation, new Promise(function (_, reject) {
      timer = setTimeout(function () {
        reject(new Error('请求超时，请检查服务端网络后重试'));
        if (controller) controller.abort();
      }, 8000);
    })]);
  } finally { clearTimeout(timer); }
}

function apiError(result) {
  if (result && result.code === -101) return new Error('登录已失效，请在 HydroBili WebUI 重新扫码');
  if (result && (result.code === -352 || result.code === -412 || (result.data && result.data.v_voucher))) {
    return new Error('B站暂时限制了本次请求，请稍后重试');
  }
  return new Error('推荐获取失败（' + (result && typeof result.code === 'number' ? result.code : '响应异常') + '），请稍后重试');
}

async function wbiKeys(auth) {
  if (wbiCache && Date.now() - wbiCache.time < 3600000) return wbiCache;
  var result = await requestJson('https://api.bilibili.com/x/web-interface/nav', auth);
  if (!result || result.code !== 0 || !result.data || !result.data.isLogin) throw apiError(result);
  var images = result.data.wbi_img || {};
  function key(url) {
    var match = String(url || '').match(/\/([a-f0-9]{32})\.[a-z]+(?:\?.*)?$/i);
    return match ? match[1] : '';
  }
  var img = key(images.img_url), sub = key(images.sub_url);
  if (!img || !sub) throw new Error('无法取得推荐签名，请稍后重试');
  wbiCache = { img: img, sub: sub, time: Date.now() };
  return wbiCache;
}

function coverUrl(url) {
  var value = String(url || '').replace(/^http:/, 'https:').replace(/^\/\//, 'https://');
  if (!/^https:\/\/(?:[a-z0-9-]+\.)*hdslb\.com\//i.test(value)) return '';
  return value.split('@')[0] + '@240w_135h_1c.jpg';
}

function count(value) {
  var n = Number(value) || 0;
  return n >= 100000000 ? (n / 100000000).toFixed(1) + '亿' : n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n);
}

function duration(value) {
  var n = Math.max(0, Math.floor(Number(value) || 0));
  return (n >= 3600 ? Math.floor(n / 3600) + ':' : '') +
    (n >= 3600 ? ('0' + Math.floor(n / 60) % 60).slice(-2) : Math.floor(n / 60)) + ':' + ('0' + n % 60).slice(-2);
}

async function recommendations(auth, force) {
  // 账号切换后绝不复用上一账号的推荐；缓存与凭据均不下发到手表存储。
  var identity = String(auth.uid || '') + ':' + auth.sessdata;
  if (feedCache && feedCache.identity !== identity) { feedCache = null; wbiCache = null; }
  if (!force && feedCache && Date.now() - feedCache.time < 120000) return feedCache.items;
  var keys = await wbiKeys(auth);
  var page = feedCache ? feedCache.page + 1 : 1;
  var query = signWbi({ fresh_type: 4, ps: FEED_LIMIT, fresh_idx: page, fresh_idx_1h: page, brush: page, web_location: 1430650 }, keys.img, keys.sub, Math.floor(Date.now() / 1000));
  var result = await requestJson('https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd?' + query, auth);
  if (!result || result.code !== 0 || !result.data || !Array.isArray(result.data.item)) {
    wbiCache = null;
    throw apiError(result);
  }
  var seen = {};
  var items = result.data.item.filter(function (item) {
    if (!item || item.goto !== 'av' || !/^BV[a-zA-Z0-9]+$/.test(item.bvid || '') || item.business_info || seen[item.bvid]) return false;
    seen[item.bvid] = true;
    return true;
  }).slice(0, FEED_LIMIT).map(function (item) {
    return { bvid: item.bvid, title: safeText(item.title).slice(0, 100), owner: safeText(item.owner && item.owner.name).slice(0, 40),
      cover: coverUrl(item.pic), duration: duration(item.duration || item.duraion), views: count(item.stat && item.stat.view) };
  });
  feedCache = { identity: identity, page: page, items: items, time: Date.now() };
  return items;
}

function text(value, size, color) {
  return { type: 'text', props: { text: value }, style: { fontSize: size || 22, color: color || '#FFFFFF', marginBottom: 8 } };
}

function button(label) {
  return { type: 'button', props: { label: label }, events: { tap: 'refresh' },
    style: { width: '100%', height: 52, backgroundColor: '#FB7299', color: '#FFFFFF', borderRadius: 18, marginTop: 12, marginBottom: 16 } };
}

function viewTree(children) {
  return { refreshMs: 0, root: { type: 'scroll', style: { padding: 18, backgroundColor: '#000000' },
    media: { circle: { padding: 32 }, pill: { padding: 14 } }, children: [
      text('HydroBili', 30, '#FB7299')
    ].concat(children) } };
}

module.exports = {
  manifest: function () {
    return { package: { id: PKG, name: 'HydroBili', version: '0.1.0' }, apps: [{ id: 'main', name: 'HydroBili', icon: 'assets/icon.png' }] };
  },
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null;
    try {
      var auth = await kit.storage.getItem(AUTH_KEY);
      if (!auth || !auth.sessdata) {
        feedCache = null; wbiCache = null;
        return viewTree([text('尚未登录', 26), text('在手机或电脑的插件列表打开 HydroBili 界面，用哔哩哔哩客户端扫码登录。', 20, '#B7BCCB'), button('已登录，刷新')]);
      }
      var force = !!(req && req.trigger === 'event' && req.event && req.event.handler === 'refresh');
      var items = await recommendations(auth, force);
      var children = [text('首页推荐', 24), button('换一批')];
      items.forEach(function (video) {
        var nodes = [];
        if (Array.isArray(video.segments) && video.segments.length) {
          nodes.push({ type: 'gif-player', props: { segments: video.segments, width: '100%', height: 166, loop: true, autoPlay: true }, style: { width: '100%', height: 166, borderRadius: 12, marginBottom: 10 } });
        } else if (video.cover) {
          nodes.push({ type: 'image', props: { src: video.cover }, style: { width: '100%', height: 166, borderRadius: 12, marginBottom: 10 } });
        }
        nodes.push(text(video.title || '无标题视频', 22));
        nodes.push(text(video.owner || '未知 UP 主', 18, '#B7BCCB'));
        nodes.push(text(video.views + '播放 · ' + video.duration, 18, '#969CAD'));
        children.push({ type: 'column', style: { padding: 12, backgroundColor: '#000000', borderRadius: 16, marginBottom: 16 }, children: nodes });
      });
      if (!items.length) children.push(text('本批暂无视频推荐，请换一批。', 20, '#B7BCCB'));
      if (items.length) children.push(button('换一批'));
      return viewTree(children);
    } catch (error) {
      return viewTree([text('暂时无法加载', 26), text(safeText(error && error.message || '请稍后重试'), 20, '#B7BCCB'), button('重试')]);
    }
  }
};

// HydroBili — WebUI 登录，HydroApp 首页推荐。凭据只读写 server-scope storage。
// API: https://sessionhu.github.io/bilibili-API-collect/docs/video/recommend.html
// WBI: https://sessionhu.github.io/bilibili-API-collect/docs/misc/sign/wbi.html
var PKG = 'com.hydro.bili';
var AUTH_KEY = 'bili_auth';
var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
// 宿主的切片器去取视频流时必须带的头：B 站 CDN 没有 Referer/UA 会回 403
var BILI_STREAM_HEADERS = {
  'Referer': 'https://www.bilibili.com/',
  'User-Agent': UA
};
var feedCache = null;
var wbiCache = null;
// 手表同时持有整棵视图树和解码封面；每批限制数量，避免一次创建十二张卡片。
var FEED_LIMIT = 4;

// ---------------------------------------------------------------- 视频切片
//
// 切片是**宿主内置能力**（kit.media.prepareVideo）：插件只传参数（哪段视频、切成
// 多大、多少帧），宿主负责真解码真编码。插件不再自带/加载任何切片 WASM——那份
// Rust 切片器现在只作为两端编码对拍的 golden 生成器，不随插件包分发。
//
// 分段以**局域网 URL** 下发，手表自己按段取。绝不把 GIF 字节塞进回包：手表互联
// 通道单条消息只有 16KB，之前把整段 base64 塞进响应体，手表侧看到的就是
// 「点击缓冲播放，显示服务器无响应」。
var SEGMENT_MS = 1200;   // 每段 1.2 秒：手表双缓冲刚好来得及预取下一段
var SEGMENT_FPS = 5;
var MAX_SEGMENTS = 30;   // 最多 36 秒：够"动起来"，也不会把 URL 列表撑爆
// 播放区尺寸（与 detailViewTree 里 gif-player 的 height 一致）：宿主按这个盒子
// **等比内缩且不放大**，所以竖屏视频会自动变成居中的窄条，不会被拉变形。
var PLAYER_BOX_W = 448;
var PLAYER_BOX_H = 160;

var mediaJobCache = {};  // bvid → 宿主的切片作业 id

/** 释放宿主的切片作业（重新缓冲 / 换视频时调用）。 */
function releaseMediaJob(bvid) {
  var job = mediaJobCache[bvid];
  if (!job) return;
  delete mediaJobCache[bvid];
  try {
    if (typeof kit !== 'undefined' && kit.media && typeof kit.media.release === 'function') {
      kit.media.release(job);
    }
  } catch (e) {
    // 释放失败不影响功能：宿主会按空闲超时自己回收
  }
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
        reject(new Error('服务器无响应'));
        if (controller) controller.abort();
      }, 5000);
    })]);
  } finally { clearTimeout(timer); }
}

function apiError(result) {
  if (result && result.code === -101) return new Error('登录已失效，请在 HydroBili WebUI 重新扫码');
  if (result && (result.code === -352 || result.code === -412 || (result.data && result.data.v_voucher))) {
    return new Error('B站暂时限制了本次请求，请稍后重试');
  }
  return new Error('推荐获取失败（' + (result && typeof result.code === 'number' ? result.code : '服务器无响应') + '），请稍后重试');
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
  return value.split('@')[0] + '@160w_90h_1c_70q.jpg';
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
  if (feedCache && feedCache.identity !== identity) {
    feedCache = null;
    wbiCache = null;
  }
  if (!force && feedCache && Date.now() - feedCache.time < 120000 && Array.isArray(feedCache.items) && feedCache.items.length) {
    return feedCache.items;
  }
  var keys = await wbiKeys(auth);
  var page = (feedCache && typeof feedCache.page === 'number') ? feedCache.page + 1 : 1;
  if (force) {
    // 强制刷新时重置本地缓存，确保击穿缓存
    feedCache = null;
  }
  var query = signWbi({ fresh_type: 4, ps: FEED_LIMIT, fresh_idx: page, fresh_idx_1h: page, brush: page, web_location: 1430650 }, keys.img, keys.sub, Math.floor(Date.now() / 1000));
  var result = await requestJson('https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd?' + query, auth);
  if (!result || result.code !== 0 || !result.data || !Array.isArray(result.data.item)) {
    wbiCache = null;
    throw apiError(result);
  }

  var seen = {};
  var rawList = result.data.item.filter(function (item) {
    if (!item || item.goto !== 'av' || !/^BV[a-zA-Z0-9]+$/.test(item.bvid || '') || item.business_info || seen[item.bvid]) return false;
    seen[item.bvid] = true;
    return true;
  }).slice(0, FEED_LIMIT);

  // 关键：将推荐列表中的完整视频详情直接存入 detailCache，点击卡片时 0ms 秒开详情页，彻底免去二次网络请求与风控拦截
  rawList.forEach(function (item) {
    if (!item || !item.bvid) return;
    var stat = item.stat || {};
    var owner = item.owner || {};
    var cid = item.cid || 0;
    detailCache[item.bvid] = {
      data: {
        bvid: item.bvid,
        aid: item.id || item.aid,
        cid: cid,
        title: safeText(item.title).slice(0, 100),
        desc: safeText(item.desc || item.title || '暂无简介').slice(0, 300),
        cover: coverUrl(item.pic),
        rawPic: item.pic,
        owner: safeText(owner.name || '未知 UP 主').slice(0, 40),
        views: count(stat.view),
        danmaku: count(stat.danmaku),
        likes: count(stat.like),
        coins: count(stat.coin),
        favorites: count(stat.favorite),
        duration: duration(item.duration || item.duraion)
      },
      time: Date.now()
    };
  });

  var items = rawList.map(function (item) {
    return {
      bvid: item.bvid,
      title: safeText(item.title).slice(0, 100),
      owner: safeText(item.owner && item.owner.name).slice(0, 40),
      cover: coverUrl(item.pic),
      duration: duration(item.duration || item.duraion),
      views: count(item.stat && item.stat.view)
    };
  });

  feedCache = { identity: identity, page: page, items: items, time: Date.now() };
  return items;
}

function text(value, size, color) {
  return { type: 'text', props: { text: value }, style: { fontSize: size || 22, color: color || '#FFFFFF', marginBottom: 8 } };
}

function button(label, tapAction) {
  return { type: 'button', props: { label: label }, events: { tap: tapAction || 'refresh' },
    style: { width: '100%', height: 52, backgroundColor: '#FB7299', color: '#FFFFFF', borderRadius: 18, marginTop: 12, marginBottom: 16 } };
}

var detailCache = {};
var playUrlCache = {};
var segmentCache = {};

async function fetchVideoDetail(bvid, auth) {
  if (detailCache[bvid] && (Date.now() - detailCache[bvid].time < 300000)) {
    return detailCache[bvid].data;
  }
  var url = 'https://api.bilibili.com/x/web-interface/view?bvid=' + encodeURIComponent(bvid);
  var result = await requestJson(url, auth);
  if (!result || result.code !== 0 || !result.data) {
    throw apiError(result);
  }
  var d = result.data;
  var cid = d.cid || (Array.isArray(d.pages) && d.pages[0] && d.pages[0].cid) || 0;
  var stat = d.stat || {};
  var owner = d.owner || {};
  var item = {
    bvid: d.bvid || bvid,
    aid: d.aid,
    cid: cid,
    title: safeText(d.title).slice(0, 100),
    desc: safeText(d.desc || '暂无简介').slice(0, 300),
    cover: coverUrl(d.pic),
    rawPic: d.pic,
    owner: safeText(owner.name || '未知 UP 主').slice(0, 40),
    views: count(stat.view),
    danmaku: count(stat.danmaku),
    likes: count(stat.like),
    coins: count(stat.coin),
    favorites: count(stat.favorite),
    duration: duration(d.duration)
  };
  detailCache[bvid] = { data: item, time: Date.now() };
  return item;
}

/**
 * 取 360P 播放流地址（WBI 签名）。
 *
 * 返回 `{url, headers}`：切片由**宿主**去取流（kit.media.prepareVideo），所以
 * 防盗链要用的 Referer/UA 必须一起交给宿主，让它在自己的 HTTP 栈上带过去。
 */
async function fetchPlayUrl(bvid, cid, auth) {
  var cacheKey = bvid + ':' + cid;
  if (playUrlCache[cacheKey] && (Date.now() - playUrlCache[cacheKey].time < 120000)) {
    return playUrlCache[cacheKey].stream;
  }
  var keys = await wbiKeys(auth);
  var params = {
    bvid: bvid,
    cid: cid,
    qn: 16,
    fnval: 0,
    fnver: 0,
    fourk: 0,
    platform: 'html5',
    high_quality: 1
  };
  var query = signWbi(params, keys.img, keys.sub, Math.floor(Date.now() / 1000));
  var url = 'https://api.bilibili.com/x/player/wbi/playurl?' + query;
  var result = await requestJson(url, auth);
  if (!result || result.code !== 0 || !result.data) {
    throw apiError(result);
  }
  var durl = result.data.durl;
  if (!Array.isArray(durl) || !durl.length || !durl[0].url) {
    throw new Error('未获取到视频播放流');
  }
  // 优先选择非 PCDN 节点（避免类似 :4483 等导致连接超时或黑屏的节点）
  var selected = durl.find(function (item) {
    return item && item.url && !item.url.includes(':') && !item.url.includes('edge.');
  }) || durl[0];
  var streamUrl = selected.url;
  // 解决手表端播放黑屏核心优化：
  // 1. 规避 HTTPS 根证书在手表端缺失导致 SSL 握手失败，降级为直连 HTTP
  // 2. 将边缘/PCDN 域名切换为公网最宽松放行的 mirrorcos 镜像，彻底免疫 403 防盗链与 UA 拦截
  try {
    var u = new URL(streamUrl);
    u.protocol = 'http:';
    if (!u.hostname.includes('mirrorcos')) {
      u.hostname = 'upos-sz-mirrorcos.bilivideo.com';
    }
    streamUrl = u.toString();
  } catch (urlErr) {
    if (streamUrl && streamUrl.startsWith('https://')) {
      streamUrl = streamUrl.replace('https://', 'http://');
    }
  }
  playUrlCache[cacheKey] = {
    stream: { url: streamUrl, headers: BILI_STREAM_HEADERS },
    time: Date.now()
  };
  return playUrlCache[cacheKey].stream;
}

/**
 * 拉起宿主的视频切片作业，把分段清单转成手表播放器的 `segments`。
 *
 * 与旧实现的区别（这是本轮的重点）：
 *  · 画面来自**真解码**（宿主 MediaCodec）而不是插件合成的渐变帧；
 *  · 分段是 URL（宿主按段懒生成），不是塞进回包的 base64（互联通道 16KB 上限）；
 *  · 失败就**如实抛错**，由调用方回退静态封面 —— 不拿假帧糊弄。
 */
async function prepareVideoSegments(bvid, cid, auth, detail) {
  // 排障日志：插件跑在宿主里，console.log 会进桌面端应用日志（手表侧没有日志出口）
  console.log('[bili] 缓冲开始 bvid=' + bvid + ' cid=' + cid);
  if (!kit || !kit.media || typeof kit.media.prepareVideo !== 'function') {
    console.warn('[bili] 宿主没有 kit.media.prepareVideo，回退静态封面');
    throw new Error('当前宿主不支持视频切片');
  }
  var stream = await fetchPlayUrl(bvid, cid, auth);
  console.log('[bili] 取到播放地址 ' + (stream.url || '').slice(0, 80) + '…');
  var job = await kit.media.prepareVideo({
    url: stream.url,
    headers: stream.headers,
    startMs: 0,
    segmentMs: SEGMENT_MS,
    fps: SEGMENT_FPS,
    width: PLAYER_BOX_W,
    height: PLAYER_BOX_H,
    maxSegments: MAX_SEGMENTS
  });
  if (!job || job.error || !Array.isArray(job.segments) || !job.segments.length) {
    var why = (job && job.error) || '宿主未返回视频分段';
    console.warn('[bili] 切片失败: ' + why);
    throw new Error(why);
  }
  console.log('[bili] 切片就绪 job=' + job.job + ' 段数=' + job.segments.length +
    ' 尺寸=' + (job.width || '?') + 'x' + (job.height || '?') +
    ' 首段=' + (job.segments[0] && job.segments[0].url || ''));
  var segments = job.segments.map(function (s) {
    return {
      src: s.url,
      // 末段可能比 SEGMENT_MS 短，用宿主给的时长；播放器靠它决定何时切下一段
      duration: Number(s.durationMs) > 0 ? Number(s.durationMs) : SEGMENT_MS
    };
  });
  mediaJobCache[bvid] = job.job;
  segmentCache[bvid] = segments;
  // 后台预热第 3/4 段（不阻塞回树）：手表播完前两段（≈2.4 秒）时它们已经切好
  try {
    kit.media.hint(job.job, 2);
  } catch (e) {
    // 预热失败只影响流畅度，不影响正确性
  }
  return segments;
}

function detailViewTree(detail, isBuffering, errText) {
  var children = [];
  var bvid = detail.bvid;
  var cid = detail.cid;

  // 1. 动图缓冲切片播放区域 (纯 GIF 双缓冲，彻底解决手表 Vela 无视频解码黑屏)
  if (Array.isArray(detail.segments) && detail.segments.length) {
    children.push({
      id: 'detail_player',
      type: 'gif-player',
      props: {
        segments: detail.segments,
        width: '100%',
        height: 160,
        loop: true,
        autoPlay: true
      },
      style: {
        width: '100%',
        height: 160,
        borderRadius: 14,
        marginBottom: 10
      }
    });
    children.push({
      id: 'btn_rebuffering',
      type: 'button',
      props: { label: '重新缓冲' },
      events: { tap: 'buffer_video:' + bvid + ':' + cid },
      style: {
        width: '100%',
        height: 42,
        backgroundColor: '#2E323C',
        color: '#FFFFFF',
        borderRadius: 21,
        marginBottom: 12
      }
    });
  } else {
    if (detail.cover) {
      children.push({
        id: 'detail_cover',
        type: 'image',
        props: { src: detail.cover },
        style: { width: '100%', height: 160, borderRadius: 14, marginBottom: 10 }
      });
    }
    // 手表端缓冲播放按钮
    children.push({
      id: 'btn_buffer_video',
      type: 'button',
      props: { label: isBuffering ? '缓冲中…' : '缓冲播放' },
      events: { tap: 'buffer_video:' + bvid + ':' + cid },
      style: {
        width: '100%',
        height: 52,
        backgroundColor: '#FB7299',
        color: '#FFFFFF',
        borderRadius: 18,
        marginBottom: 12
      }
    });
  }

  if (errText) {
    children.push(text(errText, 18, '#FFB4AB'));
  }

  // 2. 视频标题
  children.push({
    id: 'detail_title',
    type: 'text',
    props: { text: detail.title },
    style: { fontSize: 24, color: '#FFFFFF', fontWeight: 'bold', marginBottom: 8 }
  });

  // 3. UP主 与 时长
  children.push({
    id: 'detail_owner_row',
    type: 'text',
    props: { text: 'UP: ' + detail.owner + ' · 时长: ' + detail.duration },
    style: { fontSize: 18, color: '#BAC3FF', marginBottom: 12 }
  });

  // 4. 互动数据统计栏
  children.push({
    id: 'detail_stats',
    type: 'column',
    style: {
      padding: 10,
      backgroundColor: '#1E2028',
      borderRadius: 14,
      marginBottom: 12
    },
    children: [
      {
        type: 'text',
        props: { text: '播放 ' + detail.views + ' · 弹幕 ' + detail.danmaku + ' · 点赞 ' + detail.likes },
        style: { fontSize: 18, color: '#FFFFFF', textAlign: 'center' }
      }
    ]
  });

  // 5. 视频简介
  children.push({
    id: 'detail_desc_box',
    type: 'column',
    style: {
      padding: 12,
      backgroundColor: '#14161E',
      borderRadius: 14,
      marginBottom: 20
    },
    children: [
      { type: 'text', props: { text: '简介' }, style: { fontSize: 20, color: '#FB7299', marginBottom: 6 } },
      { type: 'text', props: { text: detail.desc }, style: { fontSize: 18, color: '#B7BCCB', lineHeight: 24 } }
    ]
  });

  return {
    title: '视频详情',
    header: {
      title: '‹视频详情',
      tap: 'back_to_feed'
    },
    refreshMs: 0,
    fab: null,
    root: {
      type: 'scroll',
      props: {
        title: '视频详情',
        header: {
          title: '‹视频详情',
          tap: 'back_to_feed'
        }
      },
      style: { padding: 18, backgroundColor: '#000000' },
      media: {
        circle: { paddingLeft: 42, paddingRight: 42, paddingTop: 16, paddingBottom: 60 },
        pill: { padding: 14 }
      },
      children: children
    }
  };
}

function viewTree(children, title, fab) {
  var pageTitle = title !== undefined ? title : '首页推荐';
  var fabConfig = fab !== undefined ? fab : {
    icon: '/common/icons/refresh_32dp_FFFFFF_FILL0_wght400_GRAD0_opsz40.png',
    tap: 'refresh',
    background: '#FB7299'
  };
  return {
    title: pageTitle,
    refreshMs: 0,
    fab: fabConfig,
    root: {
      type: 'scroll',
      props: { title: pageTitle },
      style: { padding: 18, backgroundColor: '#000000' },
      media: {
        circle: { paddingLeft: 42, paddingRight: 42, paddingTop: 16, paddingBottom: 120 },
        pill: { padding: 14 }
      },
      children: children
    }
  };
}

module.exports = {
  manifest: function () {
    return { package: { id: PKG, name: 'HydroBili', version: '0.1.3' }, apps: [{ id: 'main', name: 'HydroBili', icon: 'assets/icon.png' }] };
  },
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null;
    try {
      var auth = await kit.storage.getItem(AUTH_KEY);
      if (!auth || !auth.sessdata) {
        feedCache = null; wbiCache = null;
        return viewTree([text('尚未登录', 26), text('在手机或电脑的插件列表打开 HydroBili 界面，用哔哩哔哩客户端扫码登录。', 20, '#B7BCCB'), button('已登录，刷新')], '尚未登录', null);
      }

      var handler = (req && req.event && req.event.handler) || (req && req.handler) || '';

      // 事件分支 1: 打开视频详情页
      if (handler.startsWith('open_video:')) {
        var openBvid = handler.slice('open_video:'.length);
        try {
          var openDetail = await fetchVideoDetail(openBvid, auth);
          return detailViewTree(openDetail, false, null);
        } catch (detailErr) {
          var failView = viewTree([
            text('获取视频详情失败', 24, '#FFB4AB'),
            text(safeText(detailErr && detailErr.message || '服务器无响应'), 18, '#B7BCCB'),
            button('重试', 'open_video:' + openBvid)
          ], '视频详情', null);
          failView.header = { title: '‹视频详情', tap: 'back_to_feed' };
          if (failView.root && failView.root.props) {
            failView.root.props.header = failView.header;
          }
          return failView;
        }
      }

      // 事件分支 2: 缓冲播放视频（宿主真解码切片，分段 URL 下发手表双缓冲播放器）
      if (handler.startsWith('buffer_video:') || handler.startsWith('play_video:')) {
        var prefix = handler.startsWith('buffer_video:') ? 'buffer_video:' : 'play_video:';
        var playParts = handler.slice(prefix.length).split(':');
        var playBvid = playParts[0];
        // cid 必须是正整数才信：假 cid（0/空/乱码）直接当没有，用详情里的真 cid 兜底。
        // 塞假 cid 给 B 站只会换来 -400「请求错误」，报错还不如自己兜住。
        var playCid = /^\d+$/.test(playParts[1] || '') && playParts[1] !== '0' ? playParts[1] : '';
        try {
          // 每次缓冲都重新拉起作业：重新缓冲时换一批新 URL（旧的段可能已被挤出
          // 或过期），手表播放器按 URL 变化重取，缓存也顺带打散。
          releaseMediaJob(playBvid);
          delete segmentCache[playBvid];
          var playDetail = await fetchVideoDetail(playBvid, auth);
          var segs = await prepareVideoSegments(playBvid, playCid || playDetail.cid, auth, playDetail);
          playDetail.segments = segs;
          console.log('[bili] 缓冲完成 bvid=' + playBvid + ' → 发 ' + segs.length + ' 段给手表');
          return detailViewTree(playDetail, false, null);
        } catch (playErr) {
          console.warn('[bili] 缓冲失败 bvid=' + playBvid + ': ' +
            safeText(playErr && playErr.message || '无法压制') + '（回退静态封面）');
          try {
            var fallbackDetail = await fetchVideoDetail(playBvid, auth);
            return detailViewTree(fallbackDetail, false, '切片缓冲失败: ' + safeText(playErr && playErr.message || '无法压制'));
          } catch (e) {
            var playFailView = viewTree([
              text('视频缓冲失败', 24, '#FFB4AB'),
              text(safeText(playErr && playErr.message || '无法生成切片'), 18, '#B7BCCB'),
              button('返回推荐', 'back_to_feed')
            ], '视频播放', null);
            playFailView.header = { title: '‹视频播放', tap: 'back_to_feed' };
            if (playFailView.root && playFailView.root.props) {
              playFailView.root.props.header = playFailView.header;
            }
            return playFailView;
          }
        }
      }

      // 事件分支 3: 从详情页返回推荐流
      var isBackToFeed = handler === 'back_to_feed' || handler === 'video_finish';

      var force = !isBackToFeed && !!(req && (
        handler === 'refresh' ||
        req.trigger === 'refresh' ||
        (req.event && req.event.handler === 'refresh')
      ));

      var items = await recommendations(auth, force);
      var children = [];
      items.forEach(function (video) {
        var cardId = 'bili_' + (video.bvid || Math.random().toString(36).slice(2, 8));
        var openEvent = { tap: 'open_video:' + video.bvid };
        var nodes = [];
        if (Array.isArray(video.segments) && video.segments.length) {
          nodes.push({ id: cardId + '_gif', type: 'gif-player', props: { segments: video.segments, width: '100%', height: 140, loop: true, autoPlay: true }, events: openEvent, style: { width: '100%', height: 140, borderRadius: 12, marginBottom: 8 } });
        } else if (video.cover) {
          nodes.push({ id: cardId + '_img', type: 'image', props: { src: video.cover }, events: openEvent, style: { width: '100%', height: 140, borderRadius: 12, marginBottom: 8 } });
        }
        nodes.push({ id: cardId + '_title', type: 'text', props: { text: video.title || '无标题视频' }, events: openEvent, style: { fontSize: 22, color: '#FFFFFF', marginBottom: 8 } });
        nodes.push({ id: cardId + '_owner', type: 'text', props: { text: video.owner || '未知 UP 主' }, events: openEvent, style: { fontSize: 18, color: '#B7BCCB', marginBottom: 8 } });
        nodes.push({ id: cardId + '_views', type: 'text', props: { text: video.views + '播放 · ' + video.duration }, events: openEvent, style: { fontSize: 18, color: '#969CAD', marginBottom: 4 } });
        children.push({
          id: cardId,
          type: 'column',
          style: { padding: 12, backgroundColor: '#1E2028', borderRadius: 16, marginBottom: 16 },
          events: openEvent,
          children: nodes
        });
      });
      if (!items.length) {
        children.push(text('本批暂无视频推荐，请换一批。', 20, '#B7BCCB'));
      }
      return viewTree(children, '首页推荐');
    } catch (error) {
      return viewTree([text('暂时无法加载', 26), text(safeText(error && error.message || '请稍后重试'), 20, '#B7BCCB'), button('重试')], '首页推荐', null);
    }
  }
};

// HydroPlugin: HydroChat
// Bilibili 私信助手（手表端专用版）
// 严禁包含 Emoji（Vela 系统不支持显示 Emoji，已做全局正则过滤）
// 布局：通栏居中卡片、倒序消息流（Latest-First，最新一条在顶端）、手写输入回复

var PKG = 'com.hydro.chat';
var USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// 内存中缓存会话状态与用户卡片
var userCardCache = {};
var activeTalker = null; // { id: number, name: string }

// 严格过滤 Vela 不支持的 Unicode Emoji
function cleanVelaText(str) {
  if (!str) return '';
  return String(str)
    .replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F700}-\u{1F77F}\u{1F780}-\u{1F7FF}\u{1F800}-\u{1F8FF}\u{1F900}-\u{1F9FF}\u{1FA00}-\u{1FA6F}\u{1FA70}-\u{1FAFF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
    .trim();
}

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

function formatTime(tsSeconds) {
  if (!tsSeconds) return '';
  var date = new Date(tsSeconds * 1000);
  var now = new Date();
  var hours = ('0' + date.getHours()).slice(-2);
  var minutes = ('0' + date.getMinutes()).slice(-2);
  var timeStr = hours + ':' + minutes;

  if (date.toDateString() === now.toDateString()) {
    return timeStr;
  }
  var yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) {
    return '昨天 ' + timeStr;
  }
  var month = ('0' + (date.getMonth() + 1)).slice(-2);
  var day = ('0' + date.getDate()).slice(-2);
  return month + '-' + day;
}

// Bilibili API 辅助请求
async function biliFetch(url, auth, opts) {
  opts = opts || {};
  var headers = Object.assign({
    'User-Agent': USER_AGENT,
    'Cookie': 'SESSDATA=' + auth.sessdata + '; bili_jct=' + auth.bili_jct + '; DedeUserID=' + auth.uid,
    'Referer': 'https://message.bilibili.com/'
  }, opts.headers || {});

  var fetchOpts = {
    method: opts.method || 'GET',
    headers: headers
  };
  if (opts.body) {
    fetchOpts.body = opts.body;
  }
  var res = await fetch(url, fetchOpts);
  return await res.json();
}

// 规范化头像 URL，并添加手表专用的轻量级图片后缀（~4KB，极速解码防卡顿）
function formatFaceUrl(url) {
  if (!url) return '';
  var u = String(url).trim();
  if (u.startsWith('http://')) {
    u = 'https://' + u.slice(7);
  } else if (u.startsWith('//')) {
    u = 'https:' + u;
  }
  if (u.indexOf('@') === -1 && (u.endsWith('.jpg') || u.endsWith('.png') || u.endsWith('.webp') || u.endsWith('.jpeg'))) {
    u += '@100w_100h_1c.png';
  }
  return u;
}

// 批量拉取用户信息卡片（包含昵称与头像 face）
// 参考文档：https://sessionhu.github.io/bilibili-API-collect/docs/user/info.html
async function fetchUserCards(uids, auth) {
  if (!uids || !uids.length) return userCardCache;
  var missing = [];
  for (var i = 0; i < uids.length; i++) {
    var uid = uids[i];
    // 只要查询过一次（无论是否有头像）都记录已拉取，绝不重复网络阻塞
    if (!userCardCache[uid] || !userCardCache[uid].fetched) {
      missing.push(uid);
    }
  }
  if (!missing.length) return userCardCache;

  // 使用 B站私信 IM 专属的多用户信息接口 (x/im/user_infos)，单次高效拉取带头像的完整资料
  try {
    var imRes = await biliFetch('https://api.vc.bilibili.com/x/im/user_infos?uids=' + missing.join(','), auth);
    if (imRes && imRes.code === 0 && Array.isArray(imRes.data)) {
      for (var j = 0; j < imRes.data.length; j++) {
        var card = imRes.data[j];
        if (card && card.mid) {
          userCardCache[card.mid] = {
            name: cleanVelaText(card.name || ('UID ' + card.mid)),
            face: formatFaceUrl(card.face),
            fetched: true
          };
        }
      }
    }
  } catch (e) {}

  // 对于接口中未返回或无结果的 UID，记录兜底信息并标记 fetched，避免反复发起网络请求拖慢手表
  for (var k = 0; k < missing.length; k++) {
    var mUid = missing[k];
    if (!userCardCache[mUid]) {
      userCardCache[mUid] = {
        name: 'UID ' + mUid,
        face: '',
        fetched: true
      };
    } else {
      userCardCache[mUid].fetched = true;
    }
  }

  return userCardCache;
}

// 解析私信消息内容
function parseMessageContent(msg) {
  if (!msg) return '';
  var type = msg.msg_type;
  var raw = msg.content;
  if (type === 1) { // 文本
    try {
      var parsed = JSON.parse(raw);
      return cleanVelaText(parsed.content || '');
    } catch (e) {
      return cleanVelaText(raw || '');
    }
  }
  if (type === 2) return '[图片]';
  if (type === 5) return '[撤回了一条消息]';
  if (type === 11) {
    try {
      var v = JSON.parse(raw);
      return '[视频: ' + cleanVelaText(v.title || '投稿视频') + ']';
    } catch (e) {
      return '[视频]';
    }
  }
  if (type === 12) return '[专栏文章]';
  if (type === 13) return '[番剧/音频]';
  return '[其他类型消息]';
}

module.exports = {
  manifest: function () {
    return {
      package: { id: PKG, name: 'HydroChat', version: '1.0.0' },
      apps: [{ id: 'main', name: 'HydroChat', icon: 'assets/icon.png' }]
    };
  },

  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null;

    // 读取登录凭据
    var auth = await kit.storage.getItem('bili_auth');
    if (!auth || !auth.sessdata) {
      return this.renderLoginView();
    }

    // 处理交互事件
    if (req && req.trigger === 'event' && req.event) {
      var h = req.event.handler || '';
      if (h === 'refresh') {
        // 刷新当前界面
      } else if (h.startsWith('open_chat:')) {
        var parts = h.split(':');
        var talkerId = parseInt(parts[1], 10);
        var talkerName = decodeURIComponent(parts[2] || '用户');
        activeTalker = { id: talkerId, name: talkerName };
        // 异步设置已读
        this.markAsRead(talkerId, auth);
      } else if (h === 'back_to_sessions') {
        activeTalker = null;
      } else if (h === 'send_message') {
        var text = cleanVelaText(req.event.value || '');
        if (text && activeTalker) {
          await this.sendMessage(activeTalker.id, text, auth);
        }
      }
    }

    if (activeTalker) {
      return await this.renderChatDetailView(activeTalker, auth);
    }
    return await this.renderSessionListView(auth);
  },

  async markAsRead(talkerId, auth) {
    try {
      var params = 'talker_id=' + talkerId + '&session_type=1&csrf=' + auth.bili_jct + '&csrf_token=' + auth.bili_jct;
      await biliFetch('https://api.vc.bilibili.com/session_svr/v1/session_svr/update_ack', auth, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params
      });
    } catch (e) {}
  },

  async sendMessage(talkerId, text, auth) {
    try {
      var contentJson = JSON.stringify({ content: text });
      var body = [
        'msg[sender_uid]=' + encodeURIComponent(auth.uid),
        'msg[receiver_id]=' + encodeURIComponent(talkerId),
        'msg[receiver_type]=1',
        'msg[msg_type]=1',
        'msg[msg_status]=0',
        'msg[content]=' + encodeURIComponent(contentJson),
        'msg[dev_id]=' + encodeURIComponent(uuidv4()),
        'msg[timestamp]=' + Math.floor(Date.now() / 1000),
        'csrf=' + encodeURIComponent(auth.bili_jct),
        'csrf_token=' + encodeURIComponent(auth.bili_jct),
        'build=0',
        'mobi_app=web'
      ].join('&');

      await biliFetch('https://api.vc.bilibili.com/web_im/v1/web_im/send_msg', auth, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body
      });
    } catch (e) {}
  },

  // 1. 未登录视图
  renderLoginView: function () {
    return {
      root: {
        type: 'scroll',
        style: { padding: 24 },
        media: { circle: { padding: 32 } },
        children: [
          {
            type: 'column',
            style: {
              backgroundColor: '#1E1F24',
              borderRadius: 16,
              padding: 20,
              alignItems: 'center',
              marginTop: 20
            },
            children: [
              {
                type: 'text',
                props: { text: '尚未登录 B站' },
                style: { fontSize: 26, fontWeight: 700, color: '#FB7299', marginBottom: 12 }
              },
              {
                type: 'text',
                props: { text: '请在手机或电脑打开 HydroChat 插件管理页面，使用 B 站手机端扫码授权。' },
                style: { fontSize: 18, color: '#9AA2B1', textAlign: 'center', marginBottom: 20 }
              },
              {
                type: 'button',
                props: { label: '已完成登录，点击刷新' },
                events: { tap: 'refresh' },
                style: {
                  width: '100%',
                  height: 48,
                  backgroundColor: '#FB7299',
                  borderRadius: 24,
                  color: '#FFFFFF',
                  fontWeight: 700
                }
              }
            ]
          }
        ]
      }
    };
  },

  // 2. 会话列表视图
  async renderSessionListView(auth) {
    var sessionList = [];
    try {
      // 优化为首屏拉取前 8 条活跃会话（大幅减少网络延时与手表图片解码并发，极速加载防卡顿）
      var res = await biliFetch('https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions?session_type=1&size=8', auth);
      if (res && res.code === 0 && res.data && Array.isArray(res.data.session_list)) {
        sessionList = res.data.session_list;
      }
    } catch (e) {}

    // 提取所有 talker_id，拉取用户名与头像
    var uids = sessionList.map(function (s) { return s.talker_id; });
    await fetchUserCards(uids, auth);

    var cardKids = [];

    // 顶部标题行（在圆屏下有充裕的上边距，固定高度防压缩）
    cardKids.push({
      type: 'row',
      style: {
        width: '100%',
        height: 38,
        minHeight: 38,
        flexShrink: 0,
        'flex-shrink': 0,
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 12
      },
      children: [
        {
          type: 'text',
          props: { text: '私信会话' },
          style: { fontSize: 22, fontWeight: 700, color: '#FB7299' }
        },
        {
          type: 'button',
          props: { label: '刷新' },
          events: { tap: 'refresh' },
          style: {
            width: 68,
            height: 34,
            borderRadius: 17,
            backgroundColor: '#2A2D38',
            fontSize: 15,
            color: '#FFFFFF'
          }
        }
      ]
    });

    if (sessionList.length === 0) {
      cardKids.push({
        type: 'column',
        style: {
          width: '100%',
          flexShrink: 0,
          'flex-shrink': 0,
          backgroundColor: '#1E1F24',
          borderRadius: 16,
          padding: 24,
          alignItems: 'center',
          marginTop: 20
        },
        children: [
          {
            type: 'text',
            props: { text: '暂无私信消息' },
            style: { fontSize: 20, color: '#8A8B9D' }
          }
        ]
      });
    } else {
      for (var i = 0; i < sessionList.length; i++) {
        var s = sessionList[i];
        var talkerId = s.talker_id;
        var cached = userCardCache[talkerId];
        var talkerName = 'UID ' + talkerId;
        var talkerFace = '';
        if (typeof cached === 'string') {
          talkerName = cached;
        } else if (cached && typeof cached === 'object') {
          talkerName = cached.name || talkerName;
          talkerFace = cached.face || '';
        }

        var unread = s.unread_count || 0;
        var lastMsg = parseMessageContent(s.last_msg);
        var timeStr = s.last_msg ? formatTime(s.last_msg.timestamp) : '';

        // 头像组件：L2 叶子节点，固定尺寸 42x42 防变形
        var avatarNode;
        if (talkerFace) {
          avatarNode = {
            type: 'image',
            props: { src: talkerFace },
            style: {
              width: 42,
              height: 42,
              minWidth: 42,
              minHeight: 42,
              borderRadius: 21,
              flexShrink: 0,
              'flex-shrink': 0,
              backgroundColor: '#2A2D38',
              marginRight: 10
            }
          };
        } else {
          var initialChar = (talkerName && talkerName.length > 0) ? talkerName.charAt(0) : 'U';
          avatarNode = {
            type: 'badge',
            props: { label: initialChar },
            style: {
              width: 42,
              height: 42,
              minWidth: 42,
              minHeight: 42,
              borderRadius: 21,
              flexShrink: 0,
              'flex-shrink': 0,
              backgroundColor: '#323644',
              color: '#FB7299',
              fontSize: 20,
              marginRight: 10
            }
          };
        }

        // 拼接标题行文本：避免在 L2 列里再嵌套 L3 行容器（快应用模板仅支持 2 层容器展开！）
        var titleText = talkerName;
        if (unread > 0) {
          titleText = '[' + unread + '未读] ' + titleText;
        }
        if (timeStr) {
          titleText = titleText + ' · ' + timeStr;
        }

        // 会话卡片：L1 容器（row），固定高度 68px 并设置 flexShrink: 0
        cardKids.push({
          type: 'row',
          style: {
            width: '100%',
            height: 68,
            minHeight: 68,
            flexShrink: 0,
            'flex-shrink': 0,
            backgroundColor: '#1C1E26',
            borderRadius: 14,
            padding: 10,
            marginBottom: 8,
            alignItems: 'center'
          },
          events: {
            tap: 'open_chat:' + talkerId + ':' + encodeURIComponent(talkerName)
          },
          children: [
            avatarNode,
            {
              type: 'column',
              style: {
                flex: 1,
                minWidth: 0,
                height: 48,
                justifyContent: 'center'
              },
              children: [
                {
                  type: 'text',
                  props: { text: titleText },
                  style: {
                    fontSize: 17,
                    fontWeight: 700,
                    color: unread > 0 ? '#FF5C8A' : '#FB7299',
                    lines: 1
                  }
                },
                {
                  type: 'text',
                  props: { text: lastMsg || '[暂无内容]' },
                  style: {
                    fontSize: 14,
                    color: '#D0D4DE',
                    lines: 1,
                    marginTop: 3
                  }
                }
              ]
            },
            {
              type: 'text',
              props: { text: '›' },
              style: {
                fontSize: 22,
                color: '#555C70',
                marginLeft: 8,
                marginRight: 2,
                flexShrink: 0,
                'flex-shrink': 0
              }
            }
          ]
        });
      }

      // 关键：底部安全垫高 Spacer，设置固定高度并禁止压缩，确保圆屏能顺畅滑出全部卡片
      cardKids.push({
        type: 'column',
        style: {
          width: '100%',
          height: 80,
          minHeight: 80,
          flexShrink: 0,
          'flex-shrink': 0
        }
      });
    }

    return {
      root: {
        type: 'scroll',
        style: { padding: 14 },
        media: {
          circle: { padding: 20, paddingTop: 28, paddingBottom: 64 },
          rect: { padding: 16 }
        },
        children: cardKids
      }
    };
  },

  // 3. 聊天详情视图（倒序排列：最新在最上方）
  async renderChatDetailView(talker, auth) {
    var msgs = [];
    try {
      var res = await biliFetch('https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs?talker_id=' + talker.id + '&session_type=1&size=20&sender_device_id=1&build=0&mobi_app=web', auth);
      if (res && res.code === 0 && res.data && Array.isArray(res.data.messages)) {
        msgs = res.data.messages;
      }
    } catch (e) {}

    var cached = userCardCache[talker.id];
    var talkerFace = (cached && typeof cached === 'object') ? cached.face : '';

    var myUid = parseInt(auth.uid, 10);
    var kids = [];

    // 顶部导航栏：返回 + 对方头像与名字 + 刷新（固定高度防压缩）
    var titleRowChildren = [];
    if (talkerFace) {
      titleRowChildren.push({
        type: 'image',
        props: { src: talkerFace },
        style: {
          width: 28,
          height: 28,
          minWidth: 28,
          minHeight: 28,
          borderRadius: 14,
          flexShrink: 0,
          'flex-shrink': 0,
          marginRight: 6
        }
      });
    }
    titleRowChildren.push({
      type: 'text',
      props: { text: cleanVelaText(talker.name) },
      style: { fontSize: 18, fontWeight: 700, color: '#FB7299', lines: 1 }
    });

    kids.push({
      type: 'row',
      style: {
        width: '100%',
        height: 38,
        minHeight: 38,
        flexShrink: 0,
        'flex-shrink': 0,
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 12
      },
      children: [
        {
          type: 'button',
          props: { label: '‹ 返回' },
          events: { tap: 'back_to_sessions' },
          style: {
            width: 76,
            height: 36,
            borderRadius: 18,
            backgroundColor: '#2A2D38',
            fontSize: 16,
            color: '#FFFFFF'
          }
        },
        {
          type: 'row',
          style: { alignItems: 'center' },
          children: titleRowChildren
        },
        {
          type: 'button',
          props: { label: '刷新' },
          events: { tap: 'refresh' },
          style: {
            width: 60,
            height: 36,
            borderRadius: 18,
            backgroundColor: '#2A2D38',
            fontSize: 16,
            color: '#FFFFFF'
          }
        }
      ]
    });

    // 顶部常驻手写输入框（点击直接拉起手写板，固定高度防压缩）
    kids.push({
      type: 'input',
      props: {
        placeholder: '点击手写回复...',
        label: '发送给 ' + cleanVelaText(talker.name),
        value: ''
      },
      events: {
        change: 'send_message'
      },
      style: {
        width: '100%',
        height: 44,
        minHeight: 44,
        flexShrink: 0,
        'flex-shrink': 0,
        backgroundColor: '#1E1F24',
        borderRadius: 22,
        marginBottom: 12,
        color: '#FFFFFF',
        fontSize: 16
      }
    });

    // 倒序展示的消息流
    if (msgs.length === 0) {
      kids.push({
        type: 'column',
        style: {
          width: '100%',
          flexShrink: 0,
          'flex-shrink': 0,
          backgroundColor: '#1E1F24',
          borderRadius: 14,
          padding: 20,
          alignItems: 'center',
          marginTop: 10
        },
        children: [
          {
            type: 'text',
            props: { text: '暂无消息记录' },
            style: { fontSize: 18, color: '#8A8B9D' }
          }
        ]
      });
    } else {
      for (var i = 0; i < msgs.length; i++) {
        var m = msgs[i];
        var isMe = (m.sender_uid === myUid);
        var content = parseMessageContent(m);
        var timeStr = formatTime(m.timestamp);

        kids.push({
          type: 'column',
          style: {
            width: '100%',
            minHeight: 52,
            flexShrink: 0,
            'flex-shrink': 0,
            backgroundColor: isMe ? '#2E1E28' : '#20222B',
            borderRadius: 14,
            padding: 12,
            marginBottom: 8
          },
          children: [
            {
              type: 'row',
              style: {
                width: '100%',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 4
              },
              children: [
                {
                  type: 'text',
                  props: { text: isMe ? '我' : cleanVelaText(talker.name) },
                  style: {
                    fontSize: 14,
                    color: isMe ? '#FB7299' : '#9098B2',
                    fontWeight: 700
                  }
                },
                {
                  type: 'text',
                  props: { text: timeStr },
                  style: { fontSize: 12, color: '#6A6F82' }
                }
              ]
            },
            {
              type: 'text',
              props: { text: content },
              style: { fontSize: 18, color: '#FFFFFF', lineHeight: 24 }
            }
          ]
        });
      }

      // 聊天底部同样垫高，确保最早消息不被截断并禁止压缩
      kids.push({
        type: 'column',
        style: {
          width: '100%',
          height: 60,
          minHeight: 60,
          flexShrink: 0,
          'flex-shrink': 0
        }
      });
    }

    return {
      root: {
        type: 'scroll',
        style: { padding: 14 },
        media: {
          circle: { padding: 18, paddingTop: 24, paddingBottom: 56 },
          rect: { padding: 16 }
        },
        children: kids
      }
    };
  }
};

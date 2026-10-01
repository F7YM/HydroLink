// HydroPlugin: 抖音控制器 (com.float11.tiktok.control)
// 提供手表端 HydroApp 控制手机/电脑刷抖音：
// 向上/向下滑动视频、双击点赞、播放/暂停、音量控制。
// 使用 kit.input 执行输入模拟，使用 kit.storage 保存操作记录与配置。

module.exports = {
  manifest() {
    return {
      package: {
        id: 'com.float11.tiktok.control',
        name: '抖音控制器',
        author: 'float11',
        version: '1.0.0'
      },
      apps: [
        { id: 'main', name: '抖音控制器', icon: 'assets/icon.png' }
      ],
      widgets: [
        { id: 'card', name: '抖音小控', refreshMs: 0 }
      ]
    };
  },

  // 执行具体的控制手势或按键
  async performAction(action) {
    // 优先读取屏幕分辨率确定中心坐标
    let screen = { width: 1080, height: 2400 };
    try {
      const res = await kit.input.screen();
      if (res && res.width && res.height) {
        screen = res;
      }
    } catch (e) {
      // 获取失败时使用默认屏幕比例
    }

    const isDesktop = screen.width >= screen.height;
    const cx = Math.floor(screen.width / 2);
    const cy = Math.floor(screen.height / 2);

    let statusText = '';

    if (action === 'next') {
      if (isDesktop) {
        // 桌面端（网页版抖音）：Down 方向键切换下一个视频
        await kit.input.key('down');
        statusText = '已切换下一个视频';
      } else {
        // 移动端：手指由下至上滑动（下一个视频）
        const y1 = Math.floor(screen.height * 0.76);
        const y2 = Math.floor(screen.height * 0.22);
        await kit.input.swipe(cx, y1, cx, y2, 240);
        statusText = '已切换下一个视频';
      }
    } else if (action === 'prev') {
      if (isDesktop) {
        // 桌面端：Up 方向键切换上一个视频
        await kit.input.key('up');
        statusText = '已返回上一个视频';
      } else {
        // 移动端：手指由上至下滑动（上一个视频）
        const y1 = Math.floor(screen.height * 0.22);
        const y2 = Math.floor(screen.height * 0.76);
        await kit.input.swipe(cx, y1, cx, y2, 240);
        statusText = '已返回上一个视频';
      }
    } else if (action === 'like') {
      if (isDesktop) {
        // 桌面网页端快捷键 z 点赞
        await kit.input.key('z');
      }
      // 屏幕中心双击点赞
      await kit.input.click({ x: cx, y: cy, count: 2 });
      statusText = '已双击点赞';
    } else if (action === 'toggle') {
      if (isDesktop) {
        // 桌面空格暂停/播放
        await kit.input.key('space');
      } else {
        // 屏幕中心单击
        await kit.input.click({ x: cx, y: cy, count: 1 });
      }
      statusText = '已切换播放 / 暂停';
    } else if (action === 'vol_up') {
      await kit.input.key('volume_up');
      statusText = '音量 +';
    } else if (action === 'vol_down') {
      await kit.input.key('volume_down');
      statusText = '音量 -';
    }

    if (statusText) {
      const now = new Date();
      const timeStr = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
      await kit.storage.setItem('last_action', `${statusText} (${timeStr})`);
    }
  },

  async appMain(req) {
    if (req && req.trigger === 'event' && req.event && req.event.handler) {
      try {
        await this.performAction(req.event.handler);
      } catch (err) {
        await kit.storage.setItem('last_action', `执行失败: ${err.message || err}`);
      }
    }

    const lastAction = (await kit.storage.getItem('last_action')) || '就绪';

    // 文案与按钮统一的 HydroLink 风格：
    //   · 纯黑底、白色标题、次要文字 #9AA2B1
    //   · 主色按钮沿用宿主默认的 #BAC3FF（宿主把按钮文字固定成深蓝 #00237C，
    //     所以按钮底色必须是浅色，别再用深色背景——会看不见字）
    //   · 全部做成整行大按钮，不搞小胶囊
    return {
      root: {
        type: 'scroll',
        style: {
          backgroundColor: '#000000',
          padding: 16
        },
        media: {
          circle: { padding: 22 },
          rect: { padding: 16 },
          pill: { padding: 12 }
        },
        children: [
          // 页面返回导航
          {
            type: 'nav',
            props: { action: 'back', label: '‹ 返回' },
            style: { fontSize: 26, color: '#FFFFFF', marginBottom: 10 },
            media: { circle: { width: '100%', justifyContent: 'center', fontSize: 28 } }
          },

          // 标题与状态
          {
            type: 'text',
            props: { text: '抖音控制器' },
            style: { fontSize: 28, fontWeight: 700, color: '#FFFFFF', textAlign: 'center', width: '100%', marginBottom: 4 }
          },
          {
            type: 'text',
            props: { text: lastAction },
            style: { fontSize: 20, color: '#9AA2B1', textAlign: 'center', width: '100%', marginBottom: 18 }
          },

          // 主操作：下一个视频（最大、最显眼）
          {
            type: 'button',
            props: { label: '下一个视频' },
            events: { tap: 'next' },
            style: { width: '100%', height: 72, borderRadius: 36, marginBottom: 14 }
          },

          // 上一个视频 / 点赞
          {
            type: 'button',
            props: { label: '上一个视频' },
            events: { tap: 'prev' },
            style: { width: '100%', height: 60, borderRadius: 30, backgroundColor: '#9FA8CC', marginBottom: 12 }
          },
          {
            type: 'button',
            props: { label: '点赞' },
            events: { tap: 'like' },
            style: { width: '100%', height: 60, borderRadius: 30, backgroundColor: '#9FA8CC', marginBottom: 12 }
          },

          // 播放 / 暂停
          {
            type: 'button',
            props: { label: '播放 / 暂停' },
            events: { tap: 'toggle' },
            style: { width: '100%', height: 60, borderRadius: 30, backgroundColor: '#9FA8CC', marginBottom: 12 }
          },

          // 音量
          {
            type: 'button',
            props: { label: '音量 −' },
            events: { tap: 'vol_down' },
            style: { width: '100%', height: 56, borderRadius: 28, backgroundColor: '#9FA8CC', marginBottom: 10 }
          },
          {
            type: 'button',
            props: { label: '音量 +' },
            events: { tap: 'vol_up' },
            style: { width: '100%', height: 56, borderRadius: 28, backgroundColor: '#9FA8CC' }
          }
        ]
      }
    };
  },

  async widgetCard(req) {
    if (req && req.trigger === 'event' && req.event && req.event.handler) {
      try {
        await this.performAction(req.event.handler);
      } catch (e) {}
    }

    return {
      refreshMs: 0,
      root: {
        type: 'column',
        style: {
          backgroundColor: '#000000',
          padding: 12,
          borderRadius: 20,
          justifyContent: 'center'
        },
        children: [
          {
            type: 'text',
            props: { text: '抖音控制器' },
            style: { fontSize: 18, fontWeight: 700, color: '#BAC3FF', marginBottom: 8 }
          },
          {
            type: 'row',
            style: { justifyContent: 'space-between' },
            children: [
              {
                type: 'button',
                props: { label: '下一条' },
                events: { tap: 'next' },
                style: { width: '62%', height: 44, borderRadius: 22 }
              },
              {
                type: 'button',
                props: { label: '点赞' },
                events: { tap: 'like' },
                style: { width: '34%', height: 44, borderRadius: 22, backgroundColor: '#9FA8CC' }
              }
            ]
          }
        ]
      }
    };
  },

  async view(surface, item, req) {
    if (surface === 'app' && item === 'main') {
      return this.appMain(req);
    }
    if (surface === 'widget' && item === 'card') {
      return this.widgetCard(req);
    }
    return null;
  }
};

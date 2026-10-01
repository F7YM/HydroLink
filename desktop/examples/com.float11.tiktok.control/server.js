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
        statusText = '已切换下一个视频 (Down)';
      } else {
        // 移动端：手指由下至上滑动（下一个视频）
        const y1 = Math.floor(screen.height * 0.76);
        const y2 = Math.floor(screen.height * 0.22);
        await kit.input.swipe(cx, y1, cx, y2, 240);
        statusText = '已切换下一个视频 ⬇️';
      }
    } else if (action === 'prev') {
      if (isDesktop) {
        // 桌面端：Up 方向键切换上一个视频
        await kit.input.key('up');
        statusText = '已返回上一个视频 (Up)';
      } else {
        // 移动端：手指由上至下滑动（上一个视频）
        const y1 = Math.floor(screen.height * 0.22);
        const y2 = Math.floor(screen.height * 0.76);
        await kit.input.swipe(cx, y1, cx, y2, 240);
        statusText = '已返回上一个视频 ⬆️';
      }
    } else if (action === 'like') {
      if (isDesktop) {
        // 桌面网页端快捷键 z 点赞
        await kit.input.key('z');
      }
      // 屏幕中心双击点赞
      await kit.input.click({ x: cx, y: cy, count: 2 });
      statusText = '已双击点赞 ❤️';
    } else if (action === 'toggle') {
      if (isDesktop) {
        // 桌面空格暂停/播放
        await kit.input.key('space');
      } else {
        // 屏幕中心单击
        await kit.input.click({ x: cx, y: cy, count: 1 });
      }
      statusText = '已切换播放 / 暂停 ⏯';
    } else if (action === 'vol_up') {
      await kit.input.key('volume_up');
      statusText = '音量 + 🔊';
    } else if (action === 'vol_down') {
      await kit.input.key('volume_down');
      statusText = '音量 - 🔉';
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

    const lastAction = (await kit.storage.getItem('last_action')) || '就绪，点击下方按钮控制';

    return {
      root: {
        type: 'scroll',
        style: {
          backgroundColor: '#0F0F14',
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
            props: { action: 'back', label: '‹ 退出' },
            style: { fontSize: 24, color: '#A0A5B8', marginBottom: 10 },
            media: { circle: { width: '100%', justifyContent: 'center', fontSize: 26 } }
          },

          // 标题与状态提示
          {
            type: 'row',
            style: { alignItems: 'center', justifyContent: 'center', marginBottom: 6 },
            children: [
              {
                type: 'text',
                props: { text: '抖音控制器' },
                style: { fontSize: 28, fontWeight: 700, color: '#FFFFFF' }
              }
            ]
          },
          {
            type: 'text',
            props: { text: lastAction },
            style: {
              fontSize: 18,
              color: '#25F4EE',
              textAlign: 'center',
              marginBottom: 16
            }
          },

          // 上一个视频按钮
          {
            type: 'button',
            props: { label: '▲ 上一个视频' },
            events: { tap: 'prev' },
            style: {
              backgroundColor: '#262833',
              color: '#E0E2EC',
              fontSize: 22,
              fontWeight: 600,
              height: 48,
              borderRadius: 24,
              marginBottom: 12
            }
          },

          // 最核心、最大面积的「下一个视频」按钮
          {
            type: 'button',
            props: { label: '▼ 下一个视频' },
            events: { tap: 'next' },
            style: {
              backgroundColor: '#FE2C55',
              color: '#FFFFFF',
              fontSize: 26,
              fontWeight: 700,
              height: 64,
              borderRadius: 32,
              marginBottom: 12
            }
          },

          // 点赞 与 暂停 快捷行
          {
            type: 'row',
            style: { justifyContent: 'space-between', marginBottom: 12 },
            children: [
              {
                type: 'button',
                props: { label: '❤️ 点赞' },
                events: { tap: 'like' },
                style: {
                  width: '48%',
                  backgroundColor: '#351824',
                  color: '#FE2C55',
                  fontSize: 20,
                  fontWeight: 600,
                  height: 48,
                  borderRadius: 24
                }
              },
              {
                type: 'button',
                props: { label: '⏯ 播放/暂停' },
                events: { tap: 'toggle' },
                style: {
                  width: '48%',
                  backgroundColor: '#16282E',
                  color: '#25F4EE',
                  fontSize: 20,
                  fontWeight: 600,
                  height: 48,
                  borderRadius: 24
                }
              }
            ]
          },

          // 音量控制行
          {
            type: 'row',
            style: { justifyContent: 'space-between', marginBottom: 16 },
            children: [
              {
                type: 'button',
                props: { label: '🔉 音量 -' },
                events: { tap: 'vol_down' },
                style: {
                  width: '48%',
                  backgroundColor: '#20222B',
                  color: '#C4C7D4',
                  fontSize: 18,
                  height: 42,
                  borderRadius: 21
                }
              },
              {
                type: 'button',
                props: { label: '🔊 音量 +' },
                events: { tap: 'vol_up' },
                style: {
                  width: '48%',
                  backgroundColor: '#20222B',
                  color: '#C4C7D4',
                  fontSize: 18,
                  height: 42,
                  borderRadius: 21
                }
              }
            ]
          },

          // 底部使用说明/防护提示
          {
            type: 'card',
            style: {
              backgroundColor: '#181A22',
              padding: 12,
              borderRadius: 14,
              marginBottom: 16
            },
            children: [
              {
                type: 'text',
                props: { text: '💡 提示：手机端需开启 HydroLink 无障碍服务；电脑端需允许输入控制。' },
                style: { fontSize: 16, color: '#8E919E', lineHeight: 22 }
              }
            ]
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
          backgroundColor: '#121218',
          padding: 12,
          borderRadius: 20,
          justifyContent: 'center'
        },
        children: [
          {
            type: 'text',
            props: { text: '抖音控制器' },
            style: { fontSize: 18, fontWeight: 700, color: '#FE2C55', marginBottom: 8 }
          },
          {
            type: 'row',
            style: { justifyContent: 'space-between' },
            children: [
              {
                type: 'button',
                props: { label: '▼ 下一条' },
                events: { tap: 'next' },
                style: {
                  width: '62%',
                  backgroundColor: '#FE2C55',
                  color: '#FFFFFF',
                  fontSize: 18,
                  fontWeight: 700,
                  height: 40,
                  borderRadius: 20
                }
              },
              {
                type: 'button',
                props: { label: '❤️' },
                events: { tap: 'like' },
                style: {
                  width: '32%',
                  backgroundColor: '#351824',
                  color: '#FE2C55',
                  fontSize: 18,
                  height: 40,
                  borderRadius: 20
                }
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

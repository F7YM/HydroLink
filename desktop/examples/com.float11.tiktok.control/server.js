// HydroPlugin: 抖音控制器 (com.float11.tiktok.control)
// 提供手表端 HydroApp 控制手机/电脑刷抖音：
// 向上/向下滑动视频、双击点赞、播放/暂停。
// 使用 kit.input 执行输入模拟。

module.exports = {
  manifest() {
    return {
      package: {
        id: 'com.float11.tiktok.control',
        name: '抖音控制器',
        author: 'float11',
        version: '1.0.8'
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
    // 滑动手势只走屏幕中段、且距离收短：以中线为中心上下各 15%（总行程 30% 屏高）。
    // 之前是 0.76↔0.22（54% 屏高），太长——容易一次划过头、也更容易触发系统边缘手势。
    const swipeUpFrom = Math.floor(screen.height * 0.65)
    const swipeUpTo = Math.floor(screen.height * 0.35)
    const SWIPE_MS = 200

    if (action === 'next') {
      if (isDesktop) {
        // 桌面端（网页版抖音）：Down 方向键切换下一个视频
        await kit.input.key('down');
      } else {
        // 移动端：手指由下至上滑动（下一个视频）
        await kit.input.swipe(cx, swipeUpFrom, cx, swipeUpTo, SWIPE_MS);
      }
    } else if (action === 'prev') {
      if (isDesktop) {
        // 桌面端：Up 方向键切换上一个视频
        await kit.input.key('up');
      } else {
        // 移动端：手指由上至下滑动（上一个视频）
        await kit.input.swipe(cx, swipeUpTo, cx, swipeUpFrom, SWIPE_MS);
      }
    } else if (action === 'like') {
      if (isDesktop) {
        // 桌面网页端快捷键 z 点赞
        await kit.input.key('z');
      }
      // 屏幕中心双击点赞
      await kit.input.click({ x: cx, y: cy, count: 2 });
    } else if (action === 'toggle') {
      if (isDesktop) {
        // 桌面空格暂停/播放
        await kit.input.key('space');
      } else {
        // 屏幕中心单击
        await kit.input.click({ x: cx, y: cy, count: 1 });
      }
    }
  },

  async appMain(req) {
    if (req && req.trigger === 'event' && req.event && req.event.handler) {
      try {
        await this.performAction(req.event.handler);
      } catch (e) {
        // 静默失败：UI 不再显示操作提示
      }
    }

    // HydroLink 风格：纯黑底、白色标题、主色 #BAC3FF。
    // 上下视频做成两枚大圆形箭头按钮；点赞 / 播放暂停为整行大按钮。
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
          // 返回
          {
            type: 'nav',
            props: { action: 'back', label: '‹ 返回' },
            style: { fontSize: 26, color: '#FFFFFF', marginBottom: 18 },
            media: { circle: { width: '100%', justifyContent: 'center', fontSize: 28 } }
          },

          // 标题
          {
            type: 'text',
            props: { text: '抖音控制器' },
            style: { fontSize: 28, fontWeight: 700, color: '#FFFFFF', textAlign: 'center', width: '100%', marginBottom: 34 }
          },

          // 上下视频：两枚大圆形箭头按钮
          {
            type: 'row',
            style: { justifyContent: 'center', alignItems: 'center', marginBottom: 40 },
            children: [
              {
                type: 'button',
                props: { label: '▲' },
                events: { tap: 'prev' },
                style: {
                  width: 112, height: 112, borderRadius: 56,
                  paddingTop: 0, paddingBottom: 0, paddingLeft: 0, paddingRight: 0,
                  backgroundColor: '#BAC3FF', marginRight: 44
                }
              },
              {
                type: 'button',
                props: { label: '▼' },
                events: { tap: 'next' },
                style: {
                  width: 112, height: 112, borderRadius: 56,
                  paddingTop: 0, paddingBottom: 0, paddingLeft: 0, paddingRight: 0,
                  backgroundColor: '#BAC3FF'
                }
              }
            ]
          },

          // 点赞 / 播放暂停：左右并列。
          // 行内 width:'%' 依赖宿主的 wrapper 宽度上提（见 vela hydro.ux buildLeaf）；
          // 老固件上会退化成竖条，所以宽高都用固定值兜底不现实——这里就用百分比。
          {
            type: 'row',
            style: { justifyContent: 'space-between' },
            children: [
              {
                type: 'button',
                props: { label: '点赞' },
                events: { tap: 'like' },
                style: {
                  width: '47%', height: 72, borderRadius: 36,
                  paddingTop: 0, paddingBottom: 0, paddingLeft: 0, paddingRight: 0,
                  backgroundColor: '#9FA8CC'
                }
              },
              {
                type: 'button',
                props: { label: '播放/暂停' },
                events: { tap: 'toggle' },
                style: {
                  width: '47%', height: 72, borderRadius: 36,
                  paddingTop: 0, paddingBottom: 0, paddingLeft: 0, paddingRight: 0,
                  backgroundColor: '#9FA8CC'
                }
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
                style: { width: '62%', height: 44, borderRadius: 22, backgroundColor: '#BAC3FF' }
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

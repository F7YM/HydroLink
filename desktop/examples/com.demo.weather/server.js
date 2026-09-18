// HydroPlugin 示例：天气（与旧的 Go 内嵌 demo 等价的 JS 插件）
// 环境与接口见 docs/README.md；kit 由 HydroKit 注入。
// 存储（docs §6）：
//   place      → scope:'server'（服务端，WebUI/手表输入都可写，双向同步）
//   powerSave  → scope:'watch'（手表端，设备本机偏好，随手表走）
module.exports = {
  manifest() {
    return {
      package: { id: 'com.demo.weather', name: '示例天气', version: '0.2.4' },
      apps: [{ id: 'main', name: '天气' }],
      widgets: [{ id: 'now', name: '当前天气', refreshMs: 60000 }]
    }
  },

  async appMain(req) {
    let title = '示例天气'

    if (req && req.trigger === 'event' && req.event) {
      if (req.event.handler === 'refresh') {
        title = '已刷新'
      } else if (req.event.handler === 'toggle') {
        const ps = await kit.storage.getItem('powerSave', { scope: 'watch' })
        await kit.storage.setItem('powerSave', !(ps === null ? true : !!ps), { scope: 'watch' })
      } else if (req.event.handler === 'place') {
        const v = req.event.value
        if (v) await kit.storage.setItem('place', v)
      }
    }

    const ps = await kit.storage.getItem('powerSave', { scope: 'watch' })
    const powerSave = ps === null ? true : !!ps
    const place = (await kit.storage.getItem('place')) || '北京'

    return {
      root: {
        type: 'scroll',
        style: { padding: 16 },
        media: { circle: { padding: 24 }, rect: { padding: 20 }, pill: { padding: 12 } },
        children: [
          // 页面导航（协议 §4）：返回按钮由插件声明；
          // 圆屏下水平居中（圆形表盘顶端惯例），方屏左对齐
          { type: 'nav', props: { action: 'back', label: '‹ 返回' },
            style: { fontSize: 26, color: '#FFFFFF', marginBottom: 12 },
            media: { circle: { width: '100%', justifyContent: 'center', fontSize: 28 } } },
          { type: 'text', props: { text: title },
            style: { fontSize: 28, fontWeight: 700, color: '#FFFFFF', marginBottom: 4 },
            media: { rect: { fontSize: 30 }, pill: { fontSize: 22 } } },
          { type: 'text', props: { text: place + ' · 23°C 晴' },
            style: { fontSize: 22, color: '#D6DCE8', marginBottom: 16 } },
          { type: 'row', style: { marginBottom: 6 }, children: [
            { type: 'text', props: { text: '23°C' },
              style: { fontSize: 44, fontWeight: 700, color: '#FFFFFF' } }
          ] },
          { type: 'row', style: { marginBottom: 14 }, children: [
            { type: 'icon', props: { name: 'battery' }, style: { width: 26, height: 26 } },
            { type: 'text', props: { text: '电量 87%' },
              style: { fontSize: 22, color: '#D6DCE8', marginLeft: 10 } }
          ] },
          { type: 'row', style: { marginBottom: 16, justifyContent: 'space-between' }, children: [
            { type: 'text', props: { text: '省电模式' }, style: { fontSize: 22, color: '#D6DCE8' } },
            { type: 'switch', props: { checked: powerSave }, events: { change: 'toggle' } }
          ] },
          { type: 'row', style: { marginBottom: 18 }, children: [
            { type: 'badge', props: { label: 'beta' }, style: { backgroundColor: '#434659' } },
            { type: 'spinner', style: { marginLeft: 14 } },
            { type: 'input', props: { placeholder: '输入地名', value: place },
              events: { change: 'place' }, style: { marginLeft: 14 } }
          ] },
          { type: 'button', props: { label: '刷新' }, events: { tap: 'refresh' },
            style: { backgroundColor: '#BAC3FF', marginBottom: 4 } }
        ]
      }
    }
  },

  async view(surface, item, req) {
    if (surface === 'app' && item === 'main') return this.appMain(req)
    if (surface === 'widget' && item === 'now') {
      return {
        refreshMs: 60000,
        root: {
          type: 'column', style: { padding: 8 },
          children: [
            { type: 'icon', props: { name: 'battery' }, style: { width: 18, height: 18 } },
            { type: 'text', props: { text: '23°C' },
              style: { fontSize: 24, fontWeight: 700, color: '#FFFFFF', marginTop: 2 } }
          ]
        }
      }
    }
    return null
  }
}

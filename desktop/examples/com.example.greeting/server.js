// HydroPlugin 示例：问候应用（跑在桌面端 WebWorker 里）
// 环境与接口见 docs/hydroplugin.md；全局对象 kit 由 HydroKit 注入。
//
// 存储演示（docs §6）：
//   - scope:'watch'  → 存手表端（名字随手表走；容器随请求上送/响应落盘）
//   - scope:'server' → 存服务端（备注；桌面的 WebUI 设置页可写，手表 App 读取）
module.exports = {
  manifest() {
    return {
      package: { id: 'com.example.greeting', name: '示例问候', version: '0.2.0' },
      apps: [{ id: 'main', name: '问候' }],
      widgets: []
    }
  },

  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null

    // 输入框事件：把名字存到手表端
    if (req && req.trigger === 'event' && req.event && req.event.handler === 'setname') {
      const v = req.event.value
      if (v) await kit.storage.setItem('name', v, { scope: 'watch' })
    }

    const name = (await kit.storage.getItem('name', { scope: 'watch' })) || '世界'
    const note = (await kit.storage.getItem('note')) || ''

    const children = [
      { type: 'text', props: { text: '你好，' + name + '！' },
        style: { fontSize: 30, color: '#FFFFFF', fontWeight: 700 } },
      { type: 'text', props: { text: '（输入名字后回车，再进一次看变化）' },
        style: { fontSize: 20, color: '#D6DCE8', marginTop: 8 } },
      { type: 'input', props: { placeholder: '输入名字', value: name },
        events: { change: 'setname' } }
    ]
    if (note) {
      children.push({ type: 'text', props: { text: '备注：' + note },
        style: { fontSize: 20, color: '#BAC3FF', marginTop: 10 } })
    }
    return { root: { type: 'column', style: { padding: 16 }, children } }
  }
}

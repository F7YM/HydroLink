// HydroPlugin 示例：问候应用（跑在桌面端 WebWorker 里）
// 环境与接口见 docs/hydroplugin.md；全局对象 kit 由 HydroKit 注入。
module.exports = {
  manifest() {
    return {
      package: { id: 'com.example.greeting', name: '示例问候', version: '0.1.0' },
      apps: [{ id: 'main', name: '问候' }],
      widgets: []
    }
  },

  // 组件树协议与内置 demo 一致；storage 演示服务端持久存储
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null
    const name = (await kit.storage.getItem('name')) || '世界'
    return {
      root: {
        type: 'column',
        style: { padding: 16 },
        children: [
          { type: 'text', props: { text: '你好，' + name + '！' },
            style: { fontSize: 30, color: '#FFFFFF', fontWeight: 700 } },
          { type: 'text', props: { text: '（输入名字后回车，再进一次看变化）' },
            style: { fontSize: 20, color: '#D6DCE8', marginTop: 8 } },
          { type: 'input', props: { placeholder: '输入名字', value: name },
            events: { change: 'setname' } }
        ]
      }
    }
  }
}

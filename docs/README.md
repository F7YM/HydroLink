# HydroPlugin 开发文档

给**写插件的人**看的。读完这一页 + 跟着做一遍，就能在手表上看到自己的插件。

> 只想了解产品是什么？看 [根目录 README](../README.md)。
> 想自己实现一个宿主（或改宿主）？看 [协议参考](99-reference.md) 与 [平台实现](../android/NOTES.md)。

## 5 分钟上手

**1. 造一个最小插件**（任何目录，两个文件）：

```
hello/
├── manifest.json
└── server.js
```

```json
// manifest.json
{
  "id": "com.demo.hello",
  "name": "你好",
  "version": "1.0.0",
  "entry": "server.js",
  "apps": [{ "id": "main", "name": "你好" }]
}
```

```js
// server.js
module.exports = {
  manifest() {
    return {
      package: { id: 'com.demo.hello', name: '你好', version: '1.0.0' },
      apps: [{ id: 'main', name: '你好' }],
    }
  },

  // 宿主唯一的入口：view(surface, item, req) → 节点树（返回 null 表示"无内容"）
  async view(surface, item, req) {
    if (surface !== 'app' || item !== 'main') return null

    // 用户点了按钮 → 事件以 req.trigger === 'event' 回到这里
    if (req.trigger === 'event' && req.event && req.event.handler === 'press') {
      const hits = (await kit.storage.getItem('hits')) || 0
      await kit.storage.setItem('hits', hits + 1)   // 默认 scope: 'server'
    }
    const hits = (await kit.storage.getItem('hits')) || 0

    return {
      root: {
        type: 'scroll',
        style: { padding: 16 },
        children: [
          { type: 'text', props: { text: '你好，手表！' },
            style: { fontSize: 30, color: '#FFFFFF' } },
          { type: 'text', props: { text: `点击 ${hits} 次` },
            style: { fontSize: 20, color: '#9AA2B1', marginTop: 8 } },
          { type: 'button', props: { label: '点我' },
            events: { tap: 'press' }, style: { marginTop: 16 } },
        ],
      },
    }
  },
}
```

要点（细节见后续章节）：

- **入口只有一个**：`view(surface, item, req)`。`surface` 是 `app` / `widget`，`item` 是清单里
  声明的条目 id。示例插件把它按 `appMain` / `widgetNow` 拆开只是写法习惯。
- 想省事也可以只导出 `view`，返回整棵树；返回 `null` 表示"这个表面没有内容"（宿主回 204）。
- 事件（点击/开关/输入）会带着 `req.trigger = 'event'` 再调一次 `view`，你处理完返回**新的树**。
- 界面里 `props.text` 之外的能力（通知、音乐、存储……）都通过全局 `kit` 调用，
  但**必须在清单里声明权限**。

**2. 打成 `.hpk`**（就是 zip）：

```bash
cd hello && zip -r ../com.demo.hello.hpk . -x '.*'
```

**3. 装进宿主**：

| 宿主 | 怎么装 |
|---|---|
| 桌面端 | 界面「插件」→ 安装插件，选 `.hpk`；确认弹窗里会列出它申请的权限 |
| 安卓端 | 界面底部「插件」Tab → 安装 .hpk；同样先弹确认框 |

**4. 在手表上看**：手表打开 HydroApps（插件启动器）→ 选「你好」。手表与宿主需在同一网络，
或经手机互联通道。

## 文档地图

| 篇 | 讲什么 | 什么时候看 |
|---|---|---|
| [01 清单与包结构](01-manifest.md) | `manifest.json` 每个字段、包内目录约定 | 刚建插件 |
| [02 视图：请求上下文与节点树](02-view.md) | `req` 里有什么、能返回哪些节点/样式、事件怎么回来 | 写界面 |
| [03 存储](03-storage.md) | `kit.storage` 的服务端/手表端两种作用域 | 要存设置或数据 |
| [04 能力与权限](04-capabilities.md) | 通知/音乐/锁屏/命令/系统信息 + 权限门控 | 要用系统能力 |
| [05 运行时：JS 与 WASM](05-runtime.md) | WASM ABI v1、Rust 工具链、两种运行时的差别 | 要写 WASM 或追求性能 |
| [06 WebUI 与平台差异](06-webui.md) | 插件设置页（两端同源 HTML）+ 两端行为差异 | 要提供设置界面 |
| [07 调试与排错](07-debugging.md) | 日志在哪看、自检脚本、踩过的坑 | 跑不起来 / 行为不对 |
| [08 打包、示例与边界](08-packaging.md) | 打包、两个完整示例、第一版不做什么 | 准备发布 |
| [99 协议参考](99-reference.md) | HTTP 路由、字段表、ABI 上下文、版本记录 | 要实现宿主 / 对齐字段 |

## 一句话理解运行模型

```
手表容器 ──HTTP/互联──▶ 宿主（桌面或手机）
                          └─ HydroKit 运行时 ──▶ 你的插件（JS 或 WASM）
                                                  ↑ 通过"宿主胶水"拿到 kit.* 能力
```

插件**跑在宿主里**（电脑或手机上），不在手表里。手表只负责渲染插件返回的节点树、
把用户操作发回来。所以：插件的性能是宿主的性能，插件的日志在宿主的日志里。

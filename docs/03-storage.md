# 存储：kit.storage

插件的存储分两处：**服务端**（宿主数据目录，随宿主持久化）与**手表端**（手表本机，随手表走）。
手表端采用"请求上下文"语义：容器把快照随每个请求上送，插件的写入随响应回传、由容器落盘。
插件代码两边写法一样，只是 `scope` 不同。

Worker 里没有 `localStorage`，Kit 提供等价接口，且**存储位置可选**：

- `scope: 'server'`（默认）：存**服务端**——宿主应用数据目录（桌面：用户配置目录；安卓：应用数据目录），随宿主持久化
- `scope: 'watch'`：存**手表端**——手表本机存储（Vela `@system.file` 的数据存储目录，参考 [Vela 数据存储文档](https://iot.mi.com/vela/quickapp/zh/features/data/file.html)），数据随手表设备走

```js
await kit.storage.setItem('place', '上海', { scope: 'watch' })   // 手表端
const v = await kit.storage.getItem('place', { scope: 'watch' }) // '上海'；不存在返回 null
await kit.storage.removeItem('place', { scope: 'watch' })
await kit.storage.clear({ scope: 'server' })
```

- 值：任意可 JSON 序列化的值（字符串/数字/布尔/对象），自动序列化
- 语义类比 `localStorage`：同插件键值隔离、异步持久化（可 await）
- **同一请求内写后可读**：`setItem(..., {scope:'watch'})` 之后紧跟着 `getItem` 能读到刚写的值
  （两端胶水都做了写穿）。否则"读 → 取反 → 写 → 重读渲染"这类开关插件会渲染出旧值，
  表现成"开关点一下关了、刷新又变回开"——真机上就是这么发现的
- 机制：`server` 端直接落盘宿主数据目录；`watch` 端经由 hydro 请求/响应协议同步——容器把手表端该插件的存储快照随请求上送（`req.storage`），响应里的 `storage` 提示字段由容器写入手的表端存储。插件代码层面无感知，`kit.storage` 接口两端一致
- 实现状态：**两端均已可用**。watch 端实现为"请求上下文"语义——容器把插件的手表端存储快照随每个 hydro 请求上送（请求头 `X-Hydro-Store`，插件在 `req.store` 读到）；`kit.storage` 的 watch 写操作累积到当前视图返回值里（`view.storage`），容器收到后合并落盘到设备本机（Vela `@system.file`）。插件代码无感知，接口两端一致

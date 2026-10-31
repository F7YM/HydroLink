# HydroChat (com.hydro.chat)

专为 HydroLink 与小米手表（Vela）设计的 Bilibili 私信助手插件。

无需复杂配置，通过 WebUI 扫码登录 B 站账号后，即可在手表端浏览近期私信列表、查阅会话消息详情，并直接唤起手表端全屏手写输入法进行文字回复。

---

## 插件特性

- **纯应用形态（No Widget）**：专注手表端沉浸式通信，不声明微件。
- **WebUI 扫码一键登录**：浏览器打开插件管理页，使用哔哩哔哩手机客户端扫一扫即可完成授权；凭据（Cookie / CSRF Token）存储于本地安全存储中。
- **倒序消息流（Latest-First）**：针对手表端无 `scrollToBottom` 控制 API 的物理限制，最新私信直接置顶在最上方第一张卡片，抬腕即看最新。
- **通栏居中卡片（Bubble Card）**：避开 466×466 圆屏四周严重裁切，居中排版，防止边缘文字被圆弧切断。
- **直接唤起手写输入法**：点击详情页顶部输入框，手表端自动激活手写板，手写识别完成后一键发送，无需打字切换。
- **严禁 Unicode Emoji**：Vela 系统原生不支持显示 Emoji，插件在接收和发送层做了全面过滤与文字标签转换，彻底告别豆腐块与方块乱码。

---

## 插件信息

- **Package ID**：`com.hydro.chat`
- **名称**：HydroChat
- **版本**：`1.0.0`
- **作者**：`HydroLink`
- **入口**：`server.js`
- **权限**：
  - `storage`：持久化存储 B 站会话凭据（SESSDATA, bili_jct, UID 等）。
  - `notify`：新消息通知提醒能力。
  - `fetch`：拉取 B 站私信、会话及发送消息 API。

---

## 使用说明

1. 在手机或电脑端打开 HydroLink，进入插件管理，找到 **HydroChat** 点击「管理/界面」打开 WebUI。
2. 使用哔哩哔哩手机客户端扫描页面中的二维码，在手机上确认授权。
3. 在手表启动器中打开 **HydroChat** 应用：
   - 首页展示所有私信会话与未读消息计数。
   - 点击任意联系人卡片进入聊天详情。
   - 详情页最新消息位于顶端，点击顶部「点击手写回复...」直接手写文字，点击完成即时发送。

---

## 文件结构

```text
com.hydro.chat/
├── manifest.json         # 插件清单（声明 main 应用与权限）
├── server.js             # 插件核心逻辑（B站 API 交互、UI 树渲染与状态机）
├── assets/
│   └── icon.png          # 应用图标 (192x192 PNG)
├── web/
│   ├── index.html        # WebUI 扫码登录与管理面板
│   └── qrcode.min.js     # 本地离线二维码生成库
├── pack.sh               # 打包为 .hpk 脚本
└── README.md
```

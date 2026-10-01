# 抖音控制器 (com.float11.tiktok.control)

专为 HydroLink 设计的抖音/短视频手表端控制器插件示例。

支持在手表（圆形/方形表盘）上通过大按钮一键切换下一个视频、返回上一个视频、双击点赞、暂停/播放及调节音量，同时兼容 Android 手机与 Desktop 桌面端（网页版/桌面客户端）。

---

## 插件信息

- **Package ID**：`com.float11.tiktok.control`
- **名称**：抖音控制器
- **版本**：`1.0.0`
- **作者**：`float11`
- **入口**：`server.js`
- **所用权限**：
  - `input`：用于调用屏幕滑动（手势）、点击（点赞/暂停）及按键模拟。
  - `storage`：用于保存最近控制状态记录与操作反馈。

---

## 跨端控制原理

本插件通过 `kit.input` 提供的跨端输入能力实现控制：

| 控制动作 | Android 手机端（无障碍手势） | Desktop 桌面端（网页版抖音） |
|---|---|---|
| **下一个视频 (next)** | 手指从下往上滑动 `kit.input.swipe(cx, y1, cx, y2, 240)` | 发送方向键 `kit.input.key("down")` |
| **上一个视频 (prev)** | 手指从上往下滑动 `kit.input.swipe(cx, y2, cx, y1, 240)` | 发送方向键 `kit.input.key("up")` |
| **双击点赞 (like)** | 屏幕中心双击 `kit.input.click({ count: 2 })` | 快捷键 `kit.input.key("z")` + 屏幕中心双击 |
| **播放 / 暂停 (toggle)** | 屏幕中心单击 `kit.input.click({ count: 1 })` | 空格键 `kit.input.key("space")` |
| **音量调节 (vol_up / down)** | 系统音量键 `kit.input.key("volume_up")` | 系统音量键 `kit.input.key("volume_up")` |

> **前置条件说明**：
> - **Android 端**：需在系统设置中为 HydroLink 开启「无障碍服务」权限，以支持手势与点击模拟。安装时会触发红字高危警示与 3 秒强制倒计时。
> - **Desktop 端**：系统需授权 HydroLink 辅助功能/输入控制权限（Mac/Linux/Windows）。

---

## 文件结构

```text
com.float11.tiktok.control/
├── manifest.json       # 扁平规范清单文件（声明 package/apps/widgets/permissions）
├── server.js          # 插件运行时主脚本（界面树渲染与事件动作分发）
├── assets/
│   └── icon.png       # 192x192 抖音风格高清图标
├── web/
│   └── index.html     # 插件内置 WebUI 调试与状态查看页面
├── pack.sh            # 一键打包生成 .hpk 脚本
└── README.md          # 插件说明文档
```

---

## 打包与安装

### 1. 打包
在当前目录下运行打包脚本：
```bash
bash pack.sh
```
即在 `desktop/examples/` 下生成 `com.float11.tiktok.control.hpk`。

### 2. 安装使用
- **Android App**：进入主界面底部「插件」Tab，点击右上角「安装」，选择 `com.float11.tiktok.control.hpk` 安装。安装时会弹出 `input` 高危权限红字告警及 3 秒倒计时，确认后即可在手表启动器看到「抖音控制器」。
- **桌面端 (Desktop)**：直接将 `.hpk` 放置到插件存储目录或在插件管理面板中载入。

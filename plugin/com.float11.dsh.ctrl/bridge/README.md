# DeepSeek Harness HydroLink 桥接插件

本目录包含配套的 DeepSeek Harness 官方 Cordis 轻量桥接插件，遵循 [DeepSeek Harness 插件开发指南](https://deepseek-harness.github.io/deepseek-harness/develop/basic/)。

## 作用
1. 拦截 DeepSeek Harness 的 `approval/request`（工具权限审批）和 `user-questions/request`（交互提问），让智能手表手表端能够实时显示审批卡片并一键点击「允许一次 / 拒绝」。
2. 提供免 Cookie 鉴权的 `/hydro-dsh/*` REST 辅助接口（`/ping`, `/pending`, `/approval/reply`, `/question/reply`）。

## 加载方式（仅需一条命令）

在 `deepseek-harness` 仓库根目录运行：

```bash
dsh web --patch /绝对路径/plugin/com.float11.dsh.ctrl/bridge/cordis.yml
```

> **提示**：DeepSeek Harness 要求 patch 文件中的插件路径必须为绝对路径，或直接在 DSH 根目录执行。

如果不方便加载此插件，本 HydroLink 插件同样**原生支持直连 DSH `/api`**（在 WebUI 填入 Cookie 或 Launch Token 即可浏览会话、发消息、切换模型与中断生成）。

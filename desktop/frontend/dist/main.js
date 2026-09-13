// HydroLink desktop frontend — vanilla JS over Wails bindings
// (window.go.gui.App.*). No build step required.
(function () {
  const B = () => window.go && window.go.gui && window.go.gui.App;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  let activeTab = "status";
  const tabTitles = { status: "状态", pair: "配对", commands: "命令", plugins: "插件", settings: "设置", logs: "日志" };

  function switchTab(name) {
    activeTab = name;
    document.querySelectorAll(".rail-item").forEach((t) =>
      t.classList.toggle("active", t.dataset.tab === name));
    // Non-linear page transition: re-trigger the emphasized easing animation
    document.querySelectorAll(".panel").forEach((p) => {
      const on = p.id === "tab-" + name;
      p.classList.toggle("active", on);
      if (on) { p.style.animation = "none"; void p.offsetWidth; p.style.animation = ""; }
    });
    $("pageTitle").textContent = tabTitles[name] || name;
    refresh();
  }

  document.querySelectorAll(".rail-item").forEach((t) =>
    t.addEventListener("click", () => switchTab(t.dataset.tab)));

  async function api(fn, ...args) {
    const app = B();
    if (!app) return null;
    try { return await app[fn](...args); } catch (e) { console.error(fn, e); return null; }
  }

  // ---- status ----
  async function loadStatus() {
    const st = await api("GetStatus");
    if (!st) return;
    $("versionBadge").textContent = `v${st.version} · api ${st.apiLevel}`;
    $("stAddr").textContent = `0.0.0.0:${st.port}`;
    $("stIp").textContent = st.ip;
    $("stApi").textContent = st.apiLevel;
    $("stDataDir").textContent = st.dataDir;
    $("trustedCount").textContent = st.trustedCount;

    const list = $("trustedList");
    if (!st.trustedDevices || st.trustedDevices.length === 0) {
      list.innerHTML = `<p class="empty">暂无已信任设备</p>`;
    } else {
      list.innerHTML = st.trustedDevices.map((d) => `
        <div class="item">
          <div class="meta">
            <span class="name">${esc(d.device_name || "Unknown")}</span>
            <span class="sub">${esc(d.device_id)}</span>
            <span class="sub">${esc(d.trusted_at || "")}</span>
          </div>
          <button class="icon-btn" data-del="${esc(d.device_id)}" data-name="${esc(d.device_name || "")}" title="删除">
            <svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5-1-1h-5l-1 1H5v2h14V4h-3.5z"/></svg>
          </button>
        </div>`).join("");
      list.querySelectorAll("[data-del]").forEach((b) =>
        b.addEventListener("click", () => {
          showConfirm(b.dataset.del, b.dataset.name);
        }));
    }

    // settings inputs (only when not focused)
    if (document.activeElement !== $("setPort")) $("setPort").value = st.port;
  }

  // ---- pending pairs ----
  async function loadPending() {
    const pairs = await api("GetPendingPairs");
    const count = pairs ? pairs.length : 0;
    $("pendingCount").textContent = count;
    const list = $("pendingList");
    if (!pairs || pairs.length === 0) {
      list.innerHTML = `<p class="empty">暂无配对请求</p>`;
      return;
    }
    list.innerHTML = pairs.map((p) => `
      <div class="item">
        <div class="meta">
          <span class="name">${esc(p.device_name || "Unknown")}</span>
          <span class="sub">${esc(p.device_id)}</span>
        </div>
        <div style="display:flex;gap:6px">
          <button class="ok" data-accept="${esc(p.device_id)}" data-name="${esc(p.device_name || "")}">接受</button>
          <button class="danger" data-reject="${esc(p.device_id)}" data-name="${esc(p.device_name || "")}">拒绝</button>
        </div>
      </div>`).join("");
    list.querySelectorAll("[data-accept]").forEach((b) =>
      b.addEventListener("click", async () => {
        await api("RespondPair", b.dataset.accept, "accept", b.dataset.name);
        refresh();
      }));
    list.querySelectorAll("[data-reject]").forEach((b) =>
      b.addEventListener("click", async () => {
        await api("RespondPair", b.dataset.reject, "reject", b.dataset.name);
        refresh();
      }));
  }

  // ---- commands ----
  async function loadCommands() {
    const cmds = await api("GetCommands");
    const list = $("cmdList");
    $("cmdCount").textContent = cmds ? cmds.length : 0;
    if (!cmds || cmds.length === 0) {
      list.innerHTML = `<p class="empty">暂无命令，在下方添加</p>`;
      return;
    }
    list.innerHTML = cmds.map((c, i) => `
      <div class="item">
        <div class="meta">
          <span class="name">${esc(c.name)}</span>
          <span class="sub">${esc(c.command)}</span>
        </div>
        <button class="danger" data-cdel="${i}">删除</button>
      </div>`).join("");
    list.querySelectorAll("[data-cdel]").forEach((b) =>
      b.addEventListener("click", async () => {
        await api("RemoveCommand", parseInt(b.dataset.cdel, 10));
        loadCommands();
      }));
  }

  async function addCommand() {
    const name = $("cmdName").value.trim();
    const command = $("cmdBody").value.trim();
    if (!name || !command) return;
    await api("AddCommand", name, command);
    $("cmdName").value = "";
    $("cmdBody").value = "";
    loadCommands();
  }
  $("cmdAdd").addEventListener("click", addCommand);
  $("cmdBody").addEventListener("keydown", (e) => { if (e.key === "Enter") addCommand(); });

  // ---- settings ----
  async function loadSettings() {
    const s = await api("GetSettings");
    if (!s) return;
    if (document.activeElement !== $("setPort")) $("setPort").value = s.port;
    $("setAcceptAll").checked = !!s.acceptAll;
  }

  $("setSave").addEventListener("click", async () => {
    const port = parseInt($("setPort").value, 10);
    const acceptAll = $("setAcceptAll").checked;
    const msg = $("setMsg");
    try {
      const app = B();
      await app.SaveSettings(port, acceptAll);
      msg.textContent = "已保存";
      setTimeout(() => (msg.textContent = ""), 2000);
      loadStatus();
      loadSettings();
    } catch (e) {
      msg.textContent = String(e && e.message ? e.message : e);
    }
  });

  // ---- logs ----
  async function loadLogs() {
    const logs = await api("GetLogs");
    const list = $("logList");
    if (!logs || logs.length === 0) {
      list.innerHTML = `<p class="empty">暂无日志</p>`;
      return;
    }
    list.innerHTML = logs.map((l) =>
      `<div class="log-line ${esc(l.level)}">${esc(l.text)}</div>`).join("");
    list.scrollTop = list.scrollHeight;
  }

  // ---- plugins ----
  // Dedicated WebWorker per installed plugin: it long-polls the Kit worker
  // channel and executes the plugin's server.js (blob worker from the bundle
  // returned by GetPluginBundle).
  const workers = {}; // id -> { worker, status }

  function workerStatus(id) {
    const e = workers[id];
    if (!e) return "未启动";
    if (e.status === "错误" && e.err) return "错误";
    return e.status;
  }

  function workerErr(id) {
    const e = workers[id];
    return e && e.err ? e.err : "";
  }

  function updateWorkerTags() {
    document.querySelectorAll("[data-pw]").forEach((el) => {
      el.textContent = workerStatus(el.dataset.pw);
    });
  }

  async function ensureWorker(id, version) {
    const cur = workers[id];
    // 版本变化：终止旧 worker，用新代码重建（插件重装/更新后即时生效）
    if (cur && cur.worker && version && cur.version === version) return;
    if (cur && cur.worker) {
      try { cur.worker.terminate(); } catch (e) {}
      delete workers[id];
    }
    if (cur && cur.starting) return; // 正在拉取新 bundle，等待完成
    try {
      const app = B();
      if (!app || !app.GetPluginBundle) throw new Error("Wails 绑定未就绪");
      workers[id] = { worker: null, status: "启动中", starting: true };
      updateWorkerTags();
      // Same-origin base: the worker fetches /api/... on the page origin,
      // which the asset server proxies to the API server.
      const bundle = await app.GetPluginBundle(id, window.location.origin);
      if (!bundle) throw new Error("插件代码为空");
      const blob = new Blob([bundle], { type: "text/javascript" });
      const w = new Worker(URL.createObjectURL(blob));
      const entry = { worker: w, status: "启动中", version: version };
      workers[id] = entry;
      w.onmessage = (e) => {
        if (!workers[id]) return;
        if (e.data && e.data.type === "ready") workers[id].status = "在线";
        else if (e.data && e.data.type === "error") {
          workers[id].status = "错误";
          workers[id].err = e.data.message || "worker error";
          api("PluginBridgeLog", `worker ${id}: ` + workers[id].err);
        }
        updateWorkerTags();
      };
      w.onerror = (e) => {
        if (workers[id]) {
          workers[id].status = "错误";
          workers[id].err = e && e.message ? e.message : "worker error";
          api("PluginBridgeLog", `worker ${id}: ` + workers[id].err);
        }
        updateWorkerTags();
      };
      updateWorkerTags();
    } catch (e) {
      console.error("worker start failed", id, e);
      workers[id] = { worker: null, status: "错误", err: String(e && e.message ? e.message : e) };
      api("PluginBridgeLog", `worker ${id} 启动失败: ` + workers[id].err);
      updateWorkerTags();
    }
  }

  async function loadPlugins() {
    const plugins = await api("GetPlugins");
    window.__plugins = plugins || [];
    const list = $("pluginList");
    if ($("pluginCount")) $("pluginCount").textContent = plugins ? plugins.length : 0;
    if (!plugins || plugins.length === 0) {
      list.innerHTML = `<p class="empty">暂无插件，点击上方按钮安装 .hpk</p>`;
      return;
    }
    list.innerHTML = plugins.map((p, i) => {
      const apps = (p.apps || []).map((a) => `<span class="tag" title="${esc(a.id)}">应用 ${esc(a.name || a.id)}</span>`).join("");
      const wids = (p.widgets || []).map((w) => `<span class="tag" title="${esc(w.id)}">组件 ${esc(w.name || w.id)}</span>`).join("");
      const perms = (p.permissions || []).map((x) => `<span class="tag permission">${esc(x)}</span>`).join("");
      const meta = p.error
        ? `<span class="sub" style="color:var(--md-error)">${esc(p.error)}</span>`
        : `<span class="sub">${esc(p.id)} · v${esc(p.version)} · ${esc(p.entry)}</span>`;
      return `
        <div class="item">
          <div class="meta">
            <span class="name">${esc(p.name || p.id)}${p.builtin ? ' <span class="badge builtin">内置</span>' : ""}</span>
            ${meta}
            <div class="chips">${apps}${wids}${perms}</div>
          </div>
          <div style="display:flex;gap:4px;align-items:center">
            ${p.hasWeb ? `<button class="icon-btn webui-btn" data-pweb="${esc(p.id)}" title="打开 WebUI">
              <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
            </button>` : ""}
            ${p.builtin
              ? `<span class="muted">内置</span>`
              : `<button class="icon-btn" data-pdel="${esc(p.id)}" title="卸载">
              <svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5-1-1h-5l-1 1H5v2h14V4h-3.5z"/></svg>
            </button>`}
          </div>
        </div>`;
    }).join("");
    list.querySelectorAll("[data-pdel]").forEach((b) =>
      b.addEventListener("click", () => {
        const id = b.dataset.pdel;
        const p = (plugins || []).find((x) => x.id === id) || {};
        showConfirm({
          title: "卸载插件",
          text: `确定卸载「${p.name || id}」v${p.version || ""} 吗？\n插件的服务端存储会保留。`,
          ok: "卸载",
          action: async () => { await api("UninstallPlugin", id); loadPlugins(); }
        });
      }));
    list.querySelectorAll("[data-pweb]").forEach((b) =>
      b.addEventListener("click", () => openWebUI(b.dataset.pweb)));
    // Keep every installed JS plugin's worker alive for watch requests and
    // terminate workers of uninstalled plugins.
    if (plugins) {
      const installed = new Set(plugins.map((p) => p.id));
      Object.keys(workers).forEach((id) => {
        if (!installed.has(id)) {
          const w = workers[id];
          if (w && w.worker) { try { w.worker.terminate(); } catch (e) {} }
          delete workers[id];
        }
      });
      plugins.filter((p) => !p.builtin).forEach((p) => ensureWorker(p.id, p.version));
    }
  }

  // 权限中文标签
  const PERM_LABELS = {
    notify: "发送通知", music: "控制音乐", lock: "锁屏",
    commands: "执行命令", fetch: "访问网络", storage: "数据存储"
  };
  let pendingInstall = null; // { path, info }

  function showInstallConfirm(info) {
    pendingInstall = { path: pendingInstall ? pendingInstall.path : "", info: info };
    const perms = (info.permissions || []).map((p) =>
      `<span class="tag permission">${esc(PERM_LABELS[p] || p)}</span>`).join("");
    const apps = (info.apps || []).map((a) => esc(a.name || a.id)).join("、");
    const wids = (info.widgets || []).map((w) => esc(w.name || w.id)).join("、");
    const installed = (window.__plugins || []).find((x) => x.id === info.id);
    let kind = "安装";
    if (installed) {
      kind = installed.version === info.version ? "重装" : "更新";
    }
    $("pipTitle").textContent = (info.name || info.id) + (installed ? `（${kind}）` : "");
    $("pipVersion").textContent = installed
      ? `v${installed.version} → v${info.version}`
      : `v${info.version}`;
    $("pipMeta").innerHTML = `<span class="tag">${esc(info.id)}</span>` +
      (apps ? `<span class="tag">应用：${apps}</span>` : "") +
      (wids ? `<span class="tag">组件：${wids}</span>` : "");
    $("pipPerms").innerHTML = perms || '<span class="muted">无权限声明</span>';
    $("pluginConfirmModal").classList.remove("hidden");
  }

  $("pluginInstall").addEventListener("click", async () => {
    const msg = $("pluginMsg");
    msg.textContent = "";
    try {
      const app = B();
      const path = await app.PickPluginFile();
      if (!path) return; // cancelled
      pendingInstall = { path: path, info: null };
      const info = await app.PreviewPlugin(path);
      showInstallConfirm(info);
    } catch (e) {
      const text = String(e && e.message ? e.message : e);
      if (text.includes("canceled")) { msg.textContent = ""; return; }
      msg.textContent = text;
      msg.style.color = "var(--md-error)";
      setTimeout(() => { msg.textContent = ""; msg.style.color = ""; }, 4000);
    }
  });

  $("pipCancel").addEventListener("click", () => {
    $("pluginConfirmModal").classList.add("hidden");
    pendingInstall = null;
  });

  $("pipOk").addEventListener("click", async () => {
    if (!pendingInstall || !pendingInstall.path) return;
    const msg = $("pluginMsg");
    try {
      const app = B();
      const info = await app.InstallPlugin(pendingInstall.path);
      if (info && info.id) {
        msg.textContent = `已安装 ${info.name || info.id} v${info.version}`;
        msg.style.color = "var(--md-primary)";
      }
    } catch (e) {
      const text = String(e && e.message ? e.message : e);
      msg.textContent = text;
      msg.style.color = "var(--md-error)";
      setTimeout(() => { msg.textContent = ""; msg.style.color = ""; }, 4000);
    }
    $("pluginConfirmModal").classList.add("hidden");
    pendingInstall = null;
    loadPlugins();
  });

  // ---- plugin WebUI overlay (served same-origin via the asset proxy) ----
  function openWebUI(id) {
    $("webuiTitle").textContent = "WebUI · " + id;
    $("webuiFrame").src = "/api/hydro/webui/" + encodeURIComponent(id) + "/index.html";
    $("webuiModal").classList.remove("hidden");
  }
  function closeWebUI() {
    $("webuiModal").classList.add("hidden");
    $("webuiFrame").src = "about:blank";
  }
  $("webuiClose").addEventListener("click", closeWebUI);

  function refresh() {
    loadStatus();
    loadSettings();
    loadPending();
    loadCommands();
    loadPlugins();
    loadLogs();
  }

  // ---- pairing popup (pushed from backend via Wails event) ----
  let modalPair = null;

  function showPairModal(pair) {
    modalPair = pair;
    $("pairModalText").textContent =
      `${pair.deviceName || "Unknown"} 请求连接\n(${pair.deviceId})`;
    $("pairModal").classList.remove("hidden");
    switchTab("pair");
  }

  function hidePairModal() {
    modalPair = null;
    $("pairModal").classList.add("hidden");
  }

  $("pairModalAccept").addEventListener("click", async () => {
    if (modalPair) await api("RespondPair", modalPair.deviceId, "accept", modalPair.deviceName || "");
    hidePairModal();
    refresh();
  });
  $("pairModalReject").addEventListener("click", async () => {
    if (modalPair) await api("RespondPair", modalPair.deviceId, "reject", modalPair.deviceName || "");
    hidePairModal();
    refresh();
  });

  // ---- delete confirm dialog ----
  let confirmAction = null;

  // showConfirm 支持两种用法：
  //   showConfirm(deviceId, name)            删除受信设备（旧用法）
  //   showConfirm({title, text, ok, action}) 通用二级确认
  function showConfirm(a, b) {
    if (typeof a === "string") {
      confirmAction = async () => { await api("DeleteTrustedDevice", a); loadStatus(); };
      $("confirmTitle").textContent = "删除设备";
      $("confirmText").textContent = `确定删除设备「${b || a}」吗？\n此操作不可撤销。`;
      $("confirmOk").textContent = "删除";
    } else {
      confirmAction = a.action || null;
      $("confirmTitle").textContent = a.title || "确认";
      $("confirmText").textContent = a.text || "";
      $("confirmOk").textContent = a.ok || "确定";
    }
    $("confirmModal").classList.remove("hidden");
  }

  $("confirmOk").addEventListener("click", async () => {
    const action = confirmAction;
    confirmAction = null;
    $("confirmModal").classList.add("hidden");
    if (action) await action();
  });
  $("confirmCancel").addEventListener("click", () => {
    $("confirmModal").classList.add("hidden");
    confirmAction = null;
  });

  if (window.runtime && window.runtime.EventsOn) {
    window.runtime.EventsOn("pair:request", (pair) => showPairModal(pair || {}));
    window.runtime.EventsOn("plugins:wake", (id) => { if (id) ensureWorker(String(id)); });
  }

  refresh();
  setInterval(refresh, 2000);
})();

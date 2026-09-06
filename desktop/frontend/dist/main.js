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

  async function ensureWorker(id) {
    const cur = workers[id];
    if (cur && (cur.worker || cur.starting)) return;
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
      const entry = { worker: w, status: "启动中" };
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
    const list = $("pluginList");
    $("pluginCount").textContent = plugins ? plugins.length : 0;
    if (!plugins || plugins.length === 0) {
      list.innerHTML = `<p class="empty">暂无插件，点击上方按钮安装 .hpk</p>`;
      return;
    }
    list.innerHTML = plugins.map((p, i) => {
      const apps = (p.apps || []).map((a) => `<span class="tag">app ${esc(a.id)}</span>`).join("");
      const wids = (p.widgets || []).map((w) => `<span class="tag">widget ${esc(w.id)}</span>`).join("");
      const perms = (p.permissions || []).map((x) => `<span class="tag permission">${esc(x)}</span>`).join("");
      const meta = p.error
        ? `<span class="sub" style="color:var(--md-error)">${esc(p.error)}</span>`
        : `<span class="sub">${esc(p.id)} · v${esc(p.version)} · ${esc(p.entry)}${p.installedAt ? " · " + esc(p.installedAt) : ""}</span>`;
      const stat = p.builtin
        ? ""
        : `<span class="tag" data-pw="${esc(p.id)}" title="${esc(workerErr(p.id))}">${esc(workerStatus(p.id))}</span>`;
      return `
        <div class="item">
          <div class="meta">
            <span class="name">${esc(p.name || p.id)}${p.builtin ? ' <span class="badge builtin">内置</span>' : ""}</span>
            ${meta}
            <div class="chips">${stat}${apps}${wids}${perms}</div>
          </div>
          <div style="display:flex;gap:6px;align-items:center">
            ${p.hasWeb ? `<button class="ok" data-pweb="${esc(p.id)}">界面</button>` : ""}
            ${p.builtin
              ? `<span class="muted">内置</span>`
              : `<button class="danger" data-pdel="${esc(p.id)}">卸载</button>`}
          </div>
        </div>`;
    }).join("");
    list.querySelectorAll("[data-pdel]").forEach((b) =>
      b.addEventListener("click", async () => {
        await api("UninstallPlugin", b.dataset.pdel);
        loadPlugins();
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
      plugins.filter((p) => !p.builtin).forEach((p) => ensureWorker(p.id));
    }
  }

  $("pluginInstall").addEventListener("click", async () => {
    const msg = $("pluginMsg");
    msg.textContent = "";
    try {
      const app = B();
      const info = await app.InstallPlugin();
      if (info && info.id) {
        msg.textContent = `已安装 ${info.name || info.id} v${info.version}`;
        msg.style.color = "var(--md-primary)";
      }
    } catch (e) {
      const text = String(e && e.message ? e.message : e);
      if (text.includes("canceled")) { msg.textContent = ""; return; }
      msg.textContent = text;
      msg.style.color = "var(--md-error)";
    }
    setTimeout(() => { msg.textContent = ""; msg.style.color = ""; }, 4000);
    loadPlugins();
  });

  // ---- plugin WebUI overlay (served same-origin via the asset proxy) ----
  function openWebUI(id) {
    $("webuiTitle").textContent = "插件界面 · " + id;
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
  let confirmTarget = null;

  function showConfirm(deviceId, name) {
    confirmTarget = { id: deviceId, name: name };
    $("confirmText").textContent =
      `确定删除设备「${name || deviceId}」吗？\n此操作不可撤销。`;
    $("confirmModal").classList.remove("hidden");
  }

  $("confirmOk").addEventListener("click", async () => {
    if (confirmTarget) await api("DeleteTrustedDevice", confirmTarget.id);
    $("confirmModal").classList.add("hidden");
    confirmTarget = null;
    loadStatus();
  });
  $("confirmCancel").addEventListener("click", () => {
    $("confirmModal").classList.add("hidden");
    confirmTarget = null;
  });

  if (window.runtime && window.runtime.EventsOn) {
    window.runtime.EventsOn("pair:request", (pair) => showPairModal(pair || {}));
    window.runtime.EventsOn("plugins:wake", (id) => { if (id) ensureWorker(String(id)); });
  }

  refresh();
  setInterval(refresh, 2000);
})();

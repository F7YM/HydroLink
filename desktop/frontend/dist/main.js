// HydroLink desktop frontend — vanilla JS over Wails bindings
// (window.go.gui.App.*). No build step required.
(function () {
  const B = () => window.go && window.go.gui && window.go.gui.App;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  let activeTab = "status";
  const tabTitles = { status: "状态", pair: "配对", commands: "命令", plugins: "插件", settings: "设置" };

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
    $("versionBadge").textContent = `v${st.version}`;
    $("stAddr").textContent = `0.0.0.0:${st.port}`;
    $("stIp").textContent = st.ip;
    $("stDataDir").textContent = st.dataDir;
    $("stLogFile").textContent = st.logFile || "（文件日志未启用）";
    $("stLogFile").title = st.logFile || "";
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
    $("setTray").checked = !!s.trayEnabled;
    if (s.trayError) {
      $("setMsg").textContent = "托盘不可用：" + s.trayError;
      $("setMsg").style.color = "var(--md-error)";
    }
  }

  $("setSave").addEventListener("click", async () => {
    const port = parseInt($("setPort").value, 10);
    const acceptAll = $("setAcceptAll").checked;
    const trayEnabled = $("setTray").checked;
    const msg = $("setMsg");
    try {
      const app = B();
      await app.SaveSettings(port, acceptAll, trayEnabled);
      msg.style.color = "";
      msg.textContent = "已保存";
      setTimeout(() => (msg.textContent = ""), 2000);
      loadStatus();
      loadSettings();
    } catch (e) {
      msg.style.color = "var(--md-error)";
      msg.textContent = String(e && e.message ? e.message : e);
    }
  });

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
    if ($("marketList")) loadMarket(); // 市场的「已安装/可更新」徽标依赖已装列表
    const updates = $("marketList") ? (await api("MarketUpdates")) || [] : [];
    const updateIds = new Set(updates.map((u) => (u.entry || {}).id));
    const list = $("pluginList");
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
        : `<span class="sub">${esc(p.author || "未署名")} · v${esc(p.version)} · ${esc(p.id)} · ${esc(p.entry)}</span>`;
      return `
        <div class="item">
          <div class="meta">
            <span class="name">${esc(p.name || p.id)}${p.builtin ? ' <span class="badge builtin">内置</span>' : ""}${
              updateIds.has(p.id) ? ` <span class="tag permission" data-pupdate="${esc(p.id)}" style="cursor:pointer">可更新</span>` : ""}</span>
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
    // 「可更新」→ 弹更新日志（宿主版本不够时 Go 侧会把原因写进正文，装不了）
    list.querySelectorAll("[data-pupdate]").forEach((n) =>
      n.addEventListener("click", async () => {
        const id = n.dataset.pupdate;
        const text = (await api("MarketUpdateDialog", id)) || "";
        showConfirm({
          title: "插件更新",
          text,
          ok: "继续更新",
          action: () => installFromMarket(id),
        });
      }));
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
  // pendingInstall: { path, info, fromMarket } —— fromMarket 表示包是市场下载的
  // （校验已完成、只差用户确认），确认后走 MarketInstallPackage，取消要把它丢掉。
  let pendingInstall = null; // { path, info, fromMarket }

  function showInstallConfirm(info, opts) {
    pendingInstall = {
      path: pendingInstall ? pendingInstall.path : "",
      info: info,
      fromMarket: !!(opts && opts.fromMarket),
    };
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

  $("pipCancel").addEventListener("click", async () => {
    $("pluginConfirmModal").classList.add("hidden");
    if (pendingInstall && pendingInstall.fromMarket) await api("MarketDiscardStage");
    pendingInstall = null;
  });

  // ---- 插件市场 ----
  // 索引、状态、搜索/分类、版本比较全在 Go 侧（internal/market），前端只渲染；
  // 安装走 MarketFetchPackage（下载+校验）→ 复用上面那个确认弹窗 → MarketInstallPackage。
  const marketState = { rows: [], category: "all", query: "", busy: false };

  function marketMessage(text, isError) {
    const el = $("marketMsg");
    if (!el) return;
    el.textContent = text || "";
    el.style.color = isError ? "var(--md-error)" : "";
    if (isError && text) {
      setTimeout(() => { if (el.textContent === text) { el.textContent = ""; el.style.color = ""; } }, 5000);
    }
  }

  async function loadMarket() {
    const list = $("marketList");
    if (!list) return;
    if (marketState.busy) return;
    marketState.busy = true;
    try {
      const view = await api("MarketRefresh", { query: marketState.query, category: marketState.category });
      if (!view) { marketState.busy = false; return; }
      marketState.rows = view.rows || [];
      renderMarketChips(view);
      renderMarketSource(view);
      marketMessage(view.err || "", !!view.err);
      if (!marketState.rows.length) {
        list.innerHTML = `<p class="empty">${
          (view.counts && view.counts.all) ? "没有匹配的插件" : "市场里还没有插件"
        }</p>`;
      } else {
        // 图标走 Go 拼好的绝对地址（前端不拼基址，换镜像不用改前端）
        const icons = {};
        for (const r of marketState.rows) {
          const e = r.entry || {};
          if (e.icon) icons[e.id] = (await api("MarketAssetURL", e.icon)) || "";
        }
        list.innerHTML = marketState.rows.map((r) => {
          const e = r.entry || {};
          const icon = icons[e.id] ? esc(icons[e.id]) : "";
          const cats = [catLabel(e.category)];
          if (e.permissions && e.permissions.length) cats.push(e.permissions.join(" · "));
          const note = r.status === "update" ? "可更新"
            : r.status === "incompatible" ? `需要 HydroLink v${e.min_hydrolink}+` : "";
          return `
            <div class="item" data-mrow="${esc(e.id)}">
              <img class="market-icon" src="${icon}" alt="" loading="lazy">
              <div class="meta">
                <span class="name">${esc(e.name || e.id)}</span>
                <span class="sub">v${esc(e.version)} · ${esc(e.author || "未署名")}</span>
                <div class="market-desc">${esc(e.description || "")}</div>
                <div class="chips">${cats.map((c) => `<span class="tag">${esc(c)}</span>`).join("")}${
                  note ? `<span class="tag permission">${esc(note)}</span>` : ""}</div>
              </div>
              <div style="display:flex;gap:4px;align-items:center">
                ${
                  r.status === "incompatible"
                    ? `<span class="muted">不兼容</span>`
                    : `<button class="btn" data-minstall="${esc(e.id)}">${esc(r.actionLabel || "安装")}</button>`
                }
              </div>
            </div>`;
        }).join("");
        list.querySelectorAll("[data-minstall]").forEach((b) =>
          b.addEventListener("click", () => installFromMarket(b.dataset.minstall)));
        list.querySelectorAll("[data-mrow]").forEach((n) =>
          n.addEventListener("click", (ev) => {
            if (ev.target.closest("[data-minstall]")) return;
            showMarketDetail(n.dataset.mrow);
          }));
      }
    } finally {
      marketState.busy = false;
    }
  }

  function catLabel(c) {
    return c === "game" ? "游戏" : c === "utility" ? "工具" : c === "other" ? "其他" : (c || "");
  }

  function renderMarketChips(view) {
    const el = $("marketChips");
    if (!el) return;
    const counts = view.counts || {};
    const all = [["all", "全部", counts.all || 0]].concat(
      (view.categories || []).map((c) => [c, catLabel(c), counts[c] || 0]));
    el.innerHTML = all.map(([value, label, n]) =>
      `<span class="tag${marketState.category === value ? " active" : ""}" data-mcat="${esc(value)}">${esc(label)} ${n}</span>`
    ).join("");
    el.querySelectorAll("[data-mcat]").forEach((n) =>
      n.addEventListener("click", () => {
        marketState.category = n.dataset.mcat;
        loadMarket();
      }));
  }

  function renderMarketSource(view) {
    const sel = $("marketSource");
    if (!sel) return;
    const opts = view.sources && view.sources.length
      ? view.sources
      : [{ value: "auto", label: "自动" }];
    const want = opts.map((o) => o.value).join("|") + "=>" + (view.source || "auto");
    if (sel.dataset.filled !== want) {
      sel.innerHTML = opts.map((o) => `<option value="${esc(o.value)}">${esc(o.label)}</option>`).join("");
      sel.dataset.filled = want;
      if (!sel.dataset.bound) {
        sel.dataset.bound = "1";
        sel.addEventListener("change", async () => {
          await api("MarketSetSource", sel.value);
          marketState.category = "all";
          loadMarket();
        });
      }
    }
    sel.value = view.source || "auto";
  }

  async function showMarketDetail(id) {
    const row = marketState.rows.find((r) => (r.entry || {}).id === id);
    if (!row) return;
    const e = row.entry;
    const lines = [
      `v${e.version} · ${e.author || "未署名"}`,
      `分类：${catLabel(e.category)} · 运行时：${e.runtime} · 大小：${Math.round((e.size || 0) / 1024)} KB`,
      `权限：${(e.permissions && e.permissions.length) ? e.permissions.join(", ") : "未申请权限"}`,
      `指纹：${(e.sha256 || "").slice(0, 12)}`,
      "",
      e.description || "",
    ];
    if (e.notes) lines.push("", "更新日志：", e.notes);
    showConfirm({
      title: e.name || e.id,
      text: lines.join("\n"),
      ok: row.actionLabel || "安装",
      action: () => installFromMarket(id),
    });
  }

  async function installFromMarket(id) {
    marketMessage("正在下载并校验…", false);
    try {
      const info = await api("MarketFetchPackage", id);
      if (!info || !info.id) { marketMessage("下载失败", true); return; }
      showInstallConfirm(info, { fromMarket: true });
      marketMessage("", false);
    } catch (e) {
      marketMessage(String(e && e.message ? e.message : e), true);
    }
  }

  let marketSearchTimer = null;
  document.addEventListener("DOMContentLoaded", () => {
    const input = $("marketQuery");
    if (input) {
      input.addEventListener("input", () => {
        clearTimeout(marketSearchTimer);
        marketSearchTimer = setTimeout(() => {
          marketState.query = input.value || "";
          loadMarket();
        }, 250);
      });
    }
    const btn = $("marketRefresh");
    if (btn) btn.addEventListener("click", () => { marketState.query = input ? input.value : ""; loadMarket(); });
  });

  $("pipOk").addEventListener("click", async () => {
    if (!pendingInstall) return;
    const msg = $("pluginMsg");
    const fromMarket = pendingInstall.fromMarket;
    try {
      const app = B();
      const info = fromMarket
        ? await app.MarketInstallPackage()
        : await app.InstallPlugin(pendingInstall.path);
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
    if (fromMarket) loadMarket();
  });

  // ---- plugin WebUI overlay (served same-origin via the asset proxy) ----
  function openWebUI(id) {
    const p = (window.__plugins || []).find((x) => x.id === id);
    $("webuiTitle").textContent = (p && p.name) || id;
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
  // 文案节点容错：缺节点时只跳过文案，绝不能连弹窗一起不显示
  function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  }
  function showConfirm(a, b) {
    if (typeof a === "string") {
      confirmAction = async () => { await api("DeleteTrustedDevice", a); loadStatus(); };
      setText("confirmTitle", "删除设备");
      setText("confirmText", `确定删除设备「${b || a}」吗？\n此操作不可撤销。`);
      setText("confirmOk", "删除");
    } else {
      confirmAction = a.action || null;
      setText("confirmTitle", a.title || "确认");
      setText("confirmText", a.text || "");
      setText("confirmOk", a.ok || "确定");
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

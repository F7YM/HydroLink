// HydroStation desktop frontend — vanilla JS over Wails bindings
// (window.go.gui.App.*). No build step required.
(function () {
  const B = () => window.go && window.go.gui && window.go.gui.App;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  let activeTab = "status";

  function switchTab(name) {
    activeTab = name;
    document.querySelectorAll(".tab").forEach((t) =>
      t.classList.toggle("active", t.dataset.tab === name));
    document.querySelectorAll(".panel").forEach((p) =>
      p.classList.toggle("hidden", p.id !== "tab-" + name));
    refresh();
  }

  document.querySelectorAll(".tab").forEach((t) =>
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
    $("serverDot").classList.add("on");
    $("headerSub").textContent = `listening on ${st.ip}:${st.port}`;
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
          <button class="danger" data-del="${esc(d.device_id)}">删除</button>
        </div>`).join("");
      list.querySelectorAll("[data-del]").forEach((b) =>
        b.addEventListener("click", async () => {
          await api("DeleteTrustedDevice", b.dataset.del);
          loadStatus();
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

  function refresh() {
    loadStatus();
    loadSettings();
    loadPending();
    loadCommands();
    loadLogs();
  }

  refresh();
  setInterval(refresh, 2000);
})();

"use strict";
// The demo console: the live cluster map, the object browser, and helpers that demos.js uses.
// It talks only to the controller's public and read-only internal API, plus the opt-in
// demo-control sidecar (port 8081) for docker stop, start and corrupt.

const $ = (id) => document.getElementById(id);
const CHUNK_SIZE = 1000000;
const WORKERS = ["worker1", "worker2", "worker3", "worker4"];
const CONTROL_URL = `${location.protocol}//${location.hostname}:8081`;

const state = {
  workers: [],
  replication: null,
  copies: {},          // worker id -> [{chunk_id, status}]
  seen: new Set(),     // copies already drawn, so new ones can glow once
  focus: null,         // {id, name}: the object whose tiles stand out
  control: null,       // {token, workers: {worker1: running}} when the sidecar is up
  selected: null,      // object id open in the details panel
};

// Every value from the server is escaped before it reaches the page: names and metadata are user input.
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const size = (n) => n == null ? "–" : n < 1e3 ? `${n} B` : n < 1e6 ? `${(n / 1e3).toFixed(1)} kB` : `${(n / 1e6).toFixed(2)} MB`;
const when = (t) => t ? new Date(t).toLocaleString([], { dateStyle: "medium", timeStyle: "medium" }) : "–";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const objectOf = (chunkId) => chunkId.slice(0, 36);

// A pastel hue per object, so an object's chunks can be followed across the shelves.
function hue(objectId) {
  let h = 0;
  for (const c of objectId) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}
const objectColor = (objectId) => `hsl(${hue(objectId)} 62% 74%)`;

function log(message, kind = "") {
  const item = document.createElement("li");
  item.className = kind;
  item.innerHTML = `<span>${new Date().toLocaleTimeString([], { hour12: false })}</span><span>${esc(message)}</span>`;
  $("log").prepend(item);
  while ($("log").children.length > 80) $("log").lastChild.remove();
}

let toastTimer;
function toast(message, kind = "") {
  const el = $("toast");
  el.textContent = message;
  el.className = `toast ${kind}`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3600);
}

// Calls the controller; a non-2xx answer becomes an Error carrying the API's error code and message.
async function api(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    let error = new Error(`HTTP ${response.status}`);
    try {
      const body = await response.json();
      error = new Error(`${body.message} (${response.status} ${body.error})`);
      error.code = body.error;
    } catch (e) { /* not the API's error body */ }
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

// The demo-control sidecar: every call carries the token that only this page's origin can read.
async function control(action, params, method = "POST") {
  if (!state.control) throw new Error("Demo control is not running: start it with docker compose --profile demo up -d");
  const query = new URLSearchParams({ ...params, token: state.control.token });
  const response = await fetch(`${CONTROL_URL}/${action}?${query}`, { method });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || `demo control: HTTP ${response.status}`);
  return body;
}

async function refreshControl() {
  try {
    const response = await fetch(`${CONTROL_URL}/status`, { cache: "no-store" });
    state.control = response.ok ? await response.json() : null;
  } catch (e) {
    state.control = null;
  }
  const chip = $("control-status");
  chip.textContent = state.control ? "Demo control on" : "Demo control off";
  chip.className = `chip ${state.control ? "ok" : ""}`;
}

async function refreshCluster() {
  try {
    const health = await api("/health");
    $("controller-status").textContent = health.status === "UP" ? "Controller up" : "Controller down";
    $("controller-status").className = `chip ${health.status === "UP" ? "ok" : "bad"}`;
  } catch (e) {
    $("controller-status").textContent = "Controller unreachable";
    $("controller-status").className = "chip bad";
    return;
  }
  try {
    const [{ workers }, replication] = await Promise.all([api("/internal/workers"), api("/internal/replication")]);
    const expected = await Promise.all(workers.map((w) => api(`/internal/workers/${w.worker_id}/chunks?view=expected`)));
    state.workers = workers;
    state.replication = replication;
    expected.forEach((e) => { state.copies[e.worker_id] = e.chunks; });
    renderReplication();
    renderShelves();
  } catch (e) {
    $("replication-summary").textContent = `Could not refresh the cluster: ${e.message}`;
    $("replication-summary").className = "summary bad";
  }
}

function renderReplication() {
  const r = state.replication;
  $("c-healthy").textContent = r.healthy;
  $("c-under").textContent = r.under_replicated;
  $("c-unavailable").textContent = r.unavailable;
  $("c-tombstones").textContent = r.pending_tombstones;
  const total = Math.max(r.total_chunks, 1);
  $("replication-bar").innerHTML =
    `<span class="ok" style="flex-grow:${r.healthy}"></span><span class="warn" style="flex-grow:${r.under_replicated}"></span>` +
    `<span class="bad" style="flex-grow:${r.unavailable}"></span>`;
  $("replication-bar").setAttribute("aria-label", `${r.healthy} of ${r.total_chunks} chunks healthy`);
  const full = r.under_replicated === 0 && r.unavailable === 0;
  $("replication-summary").textContent = r.total_chunks === 0
    ? "No objects stored yet. Upload a file or run a demonstration."
    : full
      ? `Fully replicated: all ${r.total_chunks} chunks have 2 healthy copies.`
      : `${r.under_replicated + r.unavailable} of ${r.total_chunks} chunks need repair${r.unavailable ? `, ${r.unavailable} with no copy left` : ""}. Repair is running.`;
  $("replication-summary").className = `summary ${full ? "ok" : "bad"}`;
  void total;
}

function renderShelves() {
  const focusId = state.focus && state.focus.id;
  $("shelves").innerHTML = WORKERS.map((id) => {
    const w = state.workers.find((x) => x.worker_id === id);
    const stopped = state.control && state.control.workers[id] === false;
    const st = w ? w.state : "UNKNOWN";
    const copies = state.copies[id] || [];
    const tiles = copies.map((c) => {
      const key = `${id}/${c.chunk_id}/${c.status}`;
      const fresh = state.seen.size > 0 && !state.seen.has(key);
      const obj = objectOf(c.chunk_id);
      const style = c.status === "HEALTHY" ? ` style="background:${objectColor(obj)}"` : "";
      const cls = ["tile", c.status, obj === focusId ? "focus" : "", fresh ? "fresh" : ""].join(" ");
      return `<i class="${cls}"${style} title="${esc(c.chunk_id)} (${esc(c.status)})"></i>`;
    }).join("");
    const actions = state.control
      ? `<div class="shelf-actions">${stopped
          ? `<button class="small" data-start="${id}">Start ${id}</button>`
          : `<button class="small danger" data-stop="${id}">Stop ${id}</button>`}</div>`
      : "";
    return `<article class="shelf ${esc(st)}" aria-label="${id}">
      <div class="shelf-head"><h3>${id}</h3>
        <span class="state ${stopped ? "STOPPED" : esc(st)}">${stopped ? "Container stopped" : esc(st.toLowerCase())}</span></div>
      <p class="shelf-meta">${w ? `Heard from ${(w.last_heartbeat_age_ms / 1000).toFixed(0)} s ago` : "Not registered"}
        <br>${copies.length} copies, ${w ? w.healthy_replicas : 0} healthy${w && w.used_bytes != null ? `, ${size(w.used_bytes)} on disk` : ""}</p>
      <div class="tiles ${focusId ? "has-focus" : ""}">${tiles || `<span class="muted">No copies</span>`}</div>
      ${actions}</article>`;
  }).join("");
  renderRecovery();
  state.seen = new Set(WORKERS.flatMap((id) => (state.copies[id] || []).map((c) => `${id}/${c.chunk_id}/${c.status}`)));
  if (state.seen.size === 0) state.seen.add("-");
}

function setFocus(id, name) {
  state.focus = id ? { id, name } : null;
  const chip = $("focus-chip");
  chip.hidden = !id;
  if (id) {
    chip.innerHTML = `<span><i class="dot" style="background:${objectColor(id)}"></i>Showing ${esc(name)}</span><button type="button" id="clear-focus">Show all</button>`;
    $("clear-focus").onclick = () => setFocus(null);
  }
  renderShelves();
}

async function stopWorker(id) {
  const stillRunning = WORKERS.filter((w) => w !== id && state.control && state.control.workers[w]).length;
  if (stillRunning < 2 && !confirm(
    `Stop ${id}? Only ${stillRunning} worker${stillRunning === 1 ? "" : "s"} would keep running. ` +
    "Uploads need 2 workers, and chunks whose copies are all on stopped workers cannot be downloaded until they start again.")) {
    return;
  }
  try {
    await control("stop", { worker: id });
    log(`Stopped the ${id} container (docker stop)`, "ok");
    toast(`${id} stopped. It turns suspect after 9 s and dead after 15 s.`);
  } catch (e) {
    log(`Could not stop ${id}: ${e.message}`, "err");
    toast(e.message, "err");
  }
  await refreshControl();
  renderShelves();
}

async function startWorker(id) {
  try {
    await control("start", { worker: id });
    log(`Started the ${id} container (docker start)`, "ok");
    toast(`${id} started. It rejoins and is reconciled in a few seconds.`);
  } catch (e) {
    log(`Could not start ${id}: ${e.message}`, "err");
    toast(e.message, "err");
  }
  await refreshControl();
  renderShelves();
}

async function startAllStopped() {
  const stopped = WORKERS.filter((w) => state.control && state.control.workers[w] === false);
  for (const id of stopped) await startWorker(id);
}

function renderRecovery() {
  const stopped = state.control ? WORKERS.filter((w) => state.control.workers[w] === false) : [];
  const box = $("recovery");
  box.hidden = stopped.length === 0;
  if (stopped.length) {
    box.innerHTML = `<span>${stopped.join(", ")} ${stopped.length === 1 ? "is" : "are"} stopped.</span>
      <button type="button" class="small primary" id="start-all">Start ${stopped.length === 1 ? stopped[0] : "all stopped workers"}</button>`;
    $("start-all").onclick = startAllStopped;
  }
}

$("shelves").addEventListener("click", (event) => {
  const stop = event.target.closest("[data-stop]");
  const start = event.target.closest("[data-start]");
  if (stop) stopWorker(stop.dataset.stop);
  if (start) startWorker(start.dataset.start);
});

// Objects

async function refreshObjects() {
  try {
    const { objects, total } = await api("/api/v1/objects?limit=1000");
    $("object-count").textContent = total === 1 ? "1 object" : `${total} objects`;
    $("objects").innerHTML = objects.length === 0
      ? `<tr><td colspan="4" class="empty">Nothing stored yet. Drop a file on the left to store it.</td></tr>`
      : objects.slice().reverse().map((o) => `
        <tr class="${o.object_id === state.selected ? "selected" : ""}">
          <td><i class="dot" style="background:${objectColor(o.object_id)}"></i><strong>${esc(o.name)}</strong>
            <div class="muted mono">${esc(o.content_type)}</div></td>
          <td class="num">${size(o.size)}</td>
          <td class="when">${when(o.created_at)}</td>
          <td><div class="row-actions">
            <button class="small" data-show="${esc(o.object_id)}">Details</button>
            <a class="button small" href="/api/v1/objects/${encodeURIComponent(o.object_id)}" download="${esc(o.name)}">Download</a>
            <button class="small danger" data-delete="${esc(o.object_id)}" data-name="${esc(o.name)}">Delete</button>
          </div></td>
        </tr>`).join("");
  } catch (e) {
    log(`Could not list objects: ${e.message}`, "err");
  }
}

function copyBadges(replicas) {
  return replicas.length === 0 ? `<span class="muted">none</span>` :
    `<span class="copies">${replicas.map((x) => `<span class="copy ${esc(x.status)}">${esc(x.worker_id)} ${esc(x.status.toLowerCase())}</span>`).join("")}</span>`;
}

async function showDetails(id, { scroll = true } = {}) {
  state.selected = id;
  try {
    const m = await api(`/api/v1/objects/${encodeURIComponent(id)}/metadata`);
    setFocus(m.object_id, m.name);
    const panel = $("details");
    panel.hidden = false;
    panel.innerHTML = `
      <div class="details-head">
        <div><h3 id="details-title"><i class="dot" style="background:${objectColor(m.object_id)}"></i>${esc(m.name)}</h3>
          <p class="muted">${size(m.size)} in ${m.chunk_count ?? 0} chunks, ${esc(m.status.toLowerCase())}</p></div>
        <div class="row-actions">
          <a class="button small" href="/api/v1/objects/${encodeURIComponent(m.object_id)}" download="${esc(m.name)}">Download</a>
          <button class="small quiet" id="close-details">Close</button></div>
      </div>
      <dl class="facts">
        <dt>Object ID</dt><dd class="mono">${esc(m.object_id)}</dd>
        <dt>Content type</dt><dd>${esc(m.content_type)}</dd>
        <dt>SHA-256</dt><dd class="mono">${esc(m.checksum_sha256)}</dd>
        <dt>Uploaded</dt><dd>${when(m.created_at)}</dd>
        <dt>Last changed</dt><dd>${when(m.updated_at)}</dd>
      </dl>
      <div class="table-wrap"><table>
        <thead><tr><th class="num">Chunk</th><th>Chunk ID</th><th class="num">Size</th><th>Copies</th></tr></thead>
        <tbody>${m.chunks.map((c) => `<tr><td class="num">${c.index}</td><td class="mono">${esc(c.chunk_id)}</td>
          <td class="num">${size(c.size)}</td><td>${copyBadges(c.replicas)}</td></tr>`).join("")
          || `<tr><td colspan="4" class="empty">An empty object has no chunks.</td></tr>`}</tbody>
      </table></div>
      <form id="patch">
        <label for="patch-body"><strong>Custom metadata</strong> <span class="muted">Saving adds or updates keys; it never removes one.</span></label>
        <textarea id="patch-body" rows="4">${esc(JSON.stringify(m.custom_metadata, null, 2))}</textarea>
        <div class="row-actions" style="justify-content:flex-start;margin-top:8px"><button class="primary small" type="submit">Save metadata</button></div>
      </form>`;
    $("close-details").onclick = () => { state.selected = null; panel.hidden = true; setFocus(null); refreshObjects(); };
    $("patch").onsubmit = async (event) => {
      event.preventDefault();
      try {
        await api(`/api/v1/objects/${encodeURIComponent(id)}/metadata`, {
          method: "PATCH", headers: { "Content-Type": "application/json" }, body: $("patch-body").value,
        });
        log(`Saved metadata of ${m.name}`, "ok");
        toast("Metadata saved");
        showDetails(id, { scroll: false });
      } catch (e) {
        log(`Could not save metadata: ${e.message}`, "err");
        toast(e.message, "err");
      }
    };
    refreshObjects();
    if (scroll) panel.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (e) {
    if (e.status === 404) { state.selected = null; $("details").hidden = true; }
    log(`Could not open ${id}: ${e.message}`, "err");
  }
}

async function removeObject(id, name) {
  if (!confirm(`Delete ${name}? Its chunks are removed from every worker.`)) return;
  try {
    await api(`/api/v1/objects/${encodeURIComponent(id)}`, { method: "DELETE" });
    log(`Deleted ${name}`, "ok");
    toast(`Deleted ${name}`);
    if (state.selected === id) { state.selected = null; $("details").hidden = true; }
    if (state.focus && state.focus.id === id) setFocus(null);
    refreshObjects();
    refreshCluster();
  } catch (e) {
    log(`Could not delete ${name}: ${e.message}`, "err");
    toast(e.message, "err");
  }
}

$("objects").addEventListener("click", (event) => {
  const show = event.target.closest("[data-show]");
  const del = event.target.closest("[data-delete]");
  if (show) showDetails(show.dataset.show);
  if (del) removeObject(del.dataset.delete, del.dataset.name);
});

// Uploads a Blob with optional custom metadata, exactly as any other client would.
async function uploadBlob(blob, name, metadata) {
  const form = new FormData();
  if (metadata) form.append("metadata", new Blob([metadata], { type: "application/json" }));
  form.append("file", blob, name);
  return api("/api/v1/objects", { method: "POST", body: form });
}

async function uploadFile(file) {
  const metadata = $("metadata").value.trim();
  log(`Uploading ${file.name} (${size(file.size)})`);
  try {
    const o = await uploadBlob(file, file.name, metadata || null);
    log(`Stored ${o.name}: ${o.chunk_count} chunks, 2 copies each`, "ok");
    toast(`Stored ${o.name} in ${o.chunk_count} chunks`);
    $("upload").reset();
    await refreshCluster();
    showDetails(o.object_id);
  } catch (e) {
    log(`Upload of ${file.name} failed: ${e.message}`, "err");
    toast(e.message, "err");
  }
}

$("file").addEventListener("change", () => { if ($("file").files[0]) uploadFile($("file").files[0]); });
const target = $("drop-target");
["dragenter", "dragover"].forEach((t) => target.addEventListener(t, (e) => { e.preventDefault(); target.classList.add("over"); }));
["dragleave", "drop"].forEach((t) => target.addEventListener(t, (e) => { e.preventDefault(); target.classList.remove("over"); }));
target.addEventListener("drop", (e) => { const f = e.dataTransfer.files[0]; if (f) uploadFile(f); });

// Start

async function tick() {
  await Promise.all([refreshControl(), refreshCluster()]);
}

tick();
refreshObjects();
setInterval(tick, 2000);
setInterval(() => { refreshObjects(); if (state.selected && !document.activeElement?.closest?.("#patch")) showDetails(state.selected, { scroll: false }); }, 6000);

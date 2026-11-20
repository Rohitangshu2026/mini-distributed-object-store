"use strict";
// Guided runs of demonstrations 1 to 6. Each run performs the same steps and checks as the matching
// scripts/demo-N-*.sh, with live evidence. Demos 5 and 6 use the demo-control sidecar for docker
// stop, start and corrupt; without it, they show the command to run in a terminal and wait.

const results = {};
let running = false;

// Helpers

function randomBytes(n) {
  const bytes = new Uint8Array(n);
  for (let i = 0; i < n; i += 65536) crypto.getRandomValues(bytes.subarray(i, Math.min(i + 65536, n)));
  return bytes;
}

async function sha256(data) {
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function download(id) {
  const response = await fetch(`/api/v1/objects/${encodeURIComponent(id)}`);
  if (!response.ok) throw new Error(`download answered HTTP ${response.status}`);
  const bytes = await response.arrayBuffer();
  return { bytes, sha: await sha256(bytes), type: response.headers.get("Content-Type"),
    length: Number(response.headers.get("Content-Length")), header: response.headers.get("X-Checksum-SHA256") };
}

// Browsers label an untyped Blob application/octet-stream, so each demo file carries the type a
// browser would give a real file of that name.
async function upload(bytes, name, metadata, type = "application/octet-stream") {
  const o = await uploadBlob(new Blob([bytes], { type }), name, metadata);
  setFocus(o.object_id, o.name);
  refreshObjects();
  return o;
}

const metadataOf = (id) => api(`/api/v1/objects/${encodeURIComponent(id)}/metadata`);
const workerState = async (id) => ((await api("/internal/workers")).workers.find((w) => w.worker_id === id) || {}).state;
async function fullyReplicated() {
  const r = await api("/internal/replication");
  return r.under_replicated === 0 && r.unavailable === 0;
}
async function liveCopies() {
  const inventories = await Promise.all(WORKERS.map((w) => api(`/internal/workers/${w}/chunks`).catch(() => ({ chunks: [] }))));
  return inventories.reduce((n, i) => n + i.chunks.length, 0);
}
const short = (sha) => `${sha.slice(0, 16)}…${sha.slice(-8)}`;

// Polls a condition every second until it holds, showing the elapsed time on the step.
async function waitFor(ctx, timeoutSeconds, check) {
  const start = Date.now();
  for (;;) {
    const result = await check().catch(() => false);
    const elapsed = Math.round((Date.now() - start) / 1000);
    if (result) return elapsed;
    if (elapsed >= timeoutSeconds) throw new Error(`still not true after ${timeoutSeconds} s`);
    ctx.note(`waiting… ${elapsed} s`);
    await sleep(1000);
  }
}

// The runner

function stepRow(label) {
  const item = document.createElement("li");
  item.className = "step running";
  item.innerHTML = `<span class="mark" aria-hidden="true"></span><div><div class="label">${esc(label)}</div><div class="detail"></div></div>`;
  $("demo-steps").appendChild(item);
  return item;
}

function makeContext() {
  let current = null;
  return {
    async step(label, fn) {
      current = stepRow(label);
      try {
        const detail = await fn();
        current.className = "step pass";
        current.querySelector(".detail").innerHTML = detail ?? "";
        return detail;
      } catch (e) {
        current.className = "step fail";
        current.querySelector(".detail").textContent = e.message;
        throw e;
      }
    },
    note(text) { if (current) current.querySelector(".detail").textContent = text; },
    // Asks the presenter to run a command in a terminal, and continues on click or when `until` holds.
    async manual(command, explanation, until) {
      current.className = "step waiting";
      const box = document.createElement("div");
      box.className = "manual";
      box.innerHTML = `<p class="muted">${esc(explanation)}</p>
        <div class="command"><code>${esc(command)}</code><button type="button" class="small">Copy</button></div>
        <div><button type="button" class="small primary">I ran it, continue</button></div>`;
      current.querySelector("div").appendChild(box);
      const [copy, done] = box.querySelectorAll("button");
      copy.onclick = () => navigator.clipboard.writeText(command).then(() => toast("Command copied"));
      await new Promise((resolve) => {
        done.onclick = resolve;
        if (until) (async () => { while (box.isConnected) { if (await until().catch(() => false)) return resolve(); await sleep(1000); } })();
      });
      box.remove();
      current.className = "step running";
    },
    evidence(html) { $("demo-evidence").insertAdjacentHTML("beforeend", html); },
    verdict(pass, text) { $("demo-evidence").insertAdjacentHTML("beforeend", `<div class="verdict ${pass ? "pass" : "fail"}">${esc(text)}</div>`); },
  };
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

// The six demonstrations

const DEMOS = [
  {
    title: "Storage",
    short: "Store and get back any binary file",
    proves: "An arbitrary binary object goes in and comes back byte for byte: the SHA-256 of the original and the download match.",
    script: "scripts/demo-1-storage.sh",
    async run(ctx) {
      const bytes = randomBytes(3500000);
      const original = await ctx.step("Create a 3.5 MB file of random bytes", async () => {
        const sha = await sha256(bytes);
        return `photo.jpg, ${size(bytes.length)}, SHA-256 <span class="mono">${short(sha)}</span>`;
      }).then(() => sha256(bytes));
      const o = await ctx.step("Upload it with POST /api/v1/objects", async () => {
        const stored = await upload(bytes, "photo.jpg", null, "image/jpeg");
        ctx.object = stored;
        return `Stored as <span class="mono">${esc(stored.object_id)}</span> in ${stored.chunk_count} chunks, type ${esc(stored.content_type)}`;
      }).then(() => ctx.object);
      const d = await ctx.step("Download it with GET /api/v1/objects/{id}", async () => {
        ctx.download = await download(o.object_id);
        return `${size(ctx.download.bytes.byteLength)} received, Content-Type ${esc(ctx.download.type)}`;
      }).then(() => ctx.download);
      await ctx.step("Compare the SHA-256 of the original and the download", async () => {
        check(d.sha === original, "the download differs from the original");
        check(o.checksum_sha256 === original, "the controller recorded a different checksum");
        check(o.content_type === "image/jpeg", `the stored content type is ${o.content_type}`);
        return "Identical, and the controller recorded the same checksum and type image/jpeg";
      });
      ctx.evidence(`<div class="hashes"><div><span>Original</span>${original}</div><div><span>Downloaded</span>${d.sha}</div>
        <div><span>Recorded</span>${esc(o.checksum_sha256)}</div></div>`);
    },
  },
  {
    title: "Chunking",
    short: "Objects are split into 1 MB chunks",
    proves: "An object of 5 × CHUNK_SIZE plus a remainder becomes 6 ordered chunks: five of exactly 1,000,000 bytes and one of 123,456, each with its own ID.",
    script: "scripts/demo-2-chunking.sh",
    async run(ctx) {
      const remainder = 123456;
      const total = 5 * CHUNK_SIZE + remainder;
      const o = await ctx.step(`Upload 5 × 1,000,000 + ${remainder.toLocaleString()} = ${total.toLocaleString()} bytes`, async () => {
        ctx.object = await upload(randomBytes(total), "data.bin");
        return `Stored as <span class="mono">${esc(ctx.object.object_id)}</span>`;
      }).then(() => ctx.object);
      const m = await ctx.step("Read its chunks from GET /api/v1/objects/{id}/metadata", async () => {
        ctx.meta = await metadataOf(o.object_id);
        return `${ctx.meta.chunks.length} chunks`;
      }).then(() => ctx.meta);
      ctx.evidence(`<div class="table-wrap"><table><thead><tr><th class="num">Index</th><th>Chunk ID</th><th class="num">Size</th><th>Copies</th></tr></thead><tbody>
        ${m.chunks.map((c) => `<tr><td class="num">${c.index}</td><td class="mono">${esc(c.chunk_id)}</td><td class="num">${c.size.toLocaleString()}</td><td>${copyBadges(c.replicas)}</td></tr>`).join("")}
        </tbody></table></div>`);
      await ctx.step("Check the sizes, order and IDs", async () => {
        check(m.chunks.length === 6, `expected 6 chunks, got ${m.chunks.length}`);
        check(m.chunks.slice(0, 5).every((c) => c.size === CHUNK_SIZE), "a full chunk is not exactly CHUNK_SIZE");
        check(m.chunks[5].size === remainder, `the last chunk holds ${m.chunks[5].size} bytes`);
        check(m.chunks.every((c, i) => c.index === i && c.chunk_id === `${o.object_id}-${String(i).padStart(6, "0")}`), "chunk IDs or order are wrong");
        check(m.chunks.reduce((n, c) => n + c.size, 0) === total, "the chunk sizes do not add up to the object size");
        return "Five full chunks, a 123,456-byte remainder, IDs object_id-000000 to -000005 in order";
      });
      await ctx.step("Check each chunk has 2 copies on different workers", async () => {
        check(m.chunks.every((c) => c.replicas.length === 2 && c.replicas[0].worker_id !== c.replicas[1].worker_id), "a chunk lacks 2 copies on different workers");
        const used = new Set(m.chunks.flatMap((c) => c.replicas.map((r) => r.worker_id)));
        return `Copies placed on ${[...used].sort().join(", ")} (new copies go to the least-loaded workers)`;
      });
    },
  },
  {
    title: "Replication",
    short: "Two copies of every chunk",
    proves: "Every chunk has 2 healthy copies on 2 different workers, and each copy is a real file in that worker's own volume.",
    script: "scripts/demo-3-replication.sh",
    async run(ctx) {
      const o = await ctx.step("Upload a 3-chunk object", async () => {
        ctx.object = await upload(randomBytes(2 * CHUNK_SIZE + 500000), "replicated.bin");
        return `Stored as <span class="mono">${esc(ctx.object.object_id)}</span>`;
      }).then(() => ctx.object);
      const m = await metadataOf(o.object_id);
      await ctx.step("Check each chunk has 2 healthy copies on 2 different workers", async () => {
        for (const c of m.chunks) {
          check(c.replicas.length === 2, `${c.chunk_id} has ${c.replicas.length} copies`);
          check(c.replicas.every((r) => r.status === "HEALTHY"), `${c.chunk_id} has a copy that is not healthy`);
          check(c.replicas[0].worker_id !== c.replicas[1].worker_id, `${c.chunk_id} has both copies on one worker`);
        }
        return m.chunks.map((c) => `chunk ${c.index}: ${c.replicas.map((r) => r.worker_id).join(" + ")}`).join(", ");
      });
      const rows = [];
      await ctx.step(state.control ? "Find each copy's file inside its worker's volume (docker exec)" : "Find each copy in its worker's live inventory", async () => {
        for (const c of m.chunks) {
          for (const r of c.replicas) {
            if (state.control) {
              const f = await control("file", { worker: r.worker_id, chunk: c.chunk_id }, "GET");
              rows.push(`<tr><td class="num">${c.index}</td><td>${esc(r.worker_id)}</td><td class="mono">${esc(f.path)}</td><td class="num">${f.size.toLocaleString()}</td></tr>`);
            } else {
              const inv = await api(`/internal/workers/${r.worker_id}/chunks`);
              const entry = inv.chunks.find((x) => x.chunk_id === c.chunk_id);
              check(entry, `${r.worker_id} does not hold ${c.chunk_id}`);
              rows.push(`<tr><td class="num">${c.index}</td><td>${esc(r.worker_id)}</td><td class="mono">${esc(entry.chunk_id)}.${esc(entry.checksum_sha256.slice(0, 12))}…</td><td class="num">${entry.size.toLocaleString()}</td></tr>`);
            }
          }
        }
        return `${rows.length} files found, one per copy`;
      });
      ctx.evidence(`<div class="table-wrap"><table><thead><tr><th class="num">Chunk</th><th>Worker</th><th>File in the worker's volume</th><th class="num">Bytes</th></tr></thead><tbody>${rows.join("")}</tbody></table></div>`);
      await ctx.step("Check every worker's disk matches the metadata", async () => {
        for (const w of WORKERS) {
          const [live, expected] = await Promise.all([api(`/internal/workers/${w}/chunks`), api(`/internal/workers/${w}/chunks?view=expected`)]);
          const a = live.chunks.map((c) => c.chunk_id).sort().join();
          const b = expected.chunks.map((c) => c.chunk_id).sort().join();
          check(a === b, `${w}'s disk and the metadata disagree`);
        }
        return "All four workers hold exactly the copies the metadata records";
      });
    },
  },
  {
    title: "Metadata",
    short: "Custom metadata, updated in place",
    proves: "Custom metadata is stored at upload, read back, and changed with PATCH, while the object's checksum and chunks stay exactly the same.",
    script: "scripts/demo-4-metadata.sh",
    async run(ctx) {
      const bytes = randomBytes(2500000);
      const original = await sha256(bytes);
      const before = await ctx.step('Upload with metadata {"project":"cc","version":1,"draft":true}', async () => {
        const o = await upload(bytes, "report.pdf", '{"project":"cc","version":1,"draft":true}', "application/pdf");
        ctx.before = await metadataOf(o.object_id);
        return `Stored as <span class="mono">${esc(o.object_id)}</span>, type ${esc(o.content_type)}`;
      }).then(() => ctx.before);
      await ctx.step("Read it back", async () => `<span class="mono">${esc(JSON.stringify(before.custom_metadata))}</span>`);
      await sleep(1100); // So the new updated_at is visibly later.
      const after = await ctx.step('PATCH {"version":2,"reviewed":true}', async () => {
        ctx.after = await api(`/api/v1/objects/${before.object_id}/metadata`, {
          method: "PATCH", headers: { "Content-Type": "application/json" }, body: '{"version":2,"reviewed":true}',
        });
        return `<span class="mono">${esc(JSON.stringify(ctx.after.custom_metadata))}</span>`;
      }).then(() => ctx.after);
      await ctx.step("Check the keys merged and the data did not change", async () => {
        check(JSON.stringify(after.custom_metadata) === '{"project":"cc","version":2,"draft":true,"reviewed":true}', "the keys did not merge as expected");
        check(after.checksum_sha256 === before.checksum_sha256 && after.size === before.size, "the checksum or size changed");
        check(JSON.stringify(after.chunks.map((c) => [c.chunk_id, c.checksum_sha256])) === JSON.stringify(before.chunks.map((c) => [c.chunk_id, c.checksum_sha256])), "the chunks changed");
        check(after.created_at === before.created_at && after.updated_at > before.updated_at, "the timestamps are wrong");
        return `Version updated, reviewed added, project and draft kept with their types; updated_at moved from ${when(before.updated_at)} to ${when(after.updated_at)}`;
      });
      await ctx.step("Check the bytes still download intact", async () => {
        const d = await download(before.object_id);
        check(d.sha === original, "the download differs from the original");
        return `SHA-256 <span class="mono">${short(d.sha)}</span> unchanged`;
      });
      await ctx.step("Check invalid metadata is rejected", async () => {
        try {
          await api(`/api/v1/objects/${before.object_id}/metadata`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: '{"nested":{"a":1}}' });
        } catch (e) {
          check(e.code === "invalid_metadata", `rejected with ${e.code}`);
          return `A nested value got 400 invalid_metadata: ${esc(e.message)}`;
        }
        throw new Error("a nested value was accepted");
      });
      ctx.evidence(`<div class="hashes"><div><span>Before</span>${esc(JSON.stringify(before.custom_metadata))}</div><div><span>After</span>${esc(JSON.stringify(after.custom_metadata))}</div>
        <div><span>Checksum</span>${esc(after.checksum_sha256)} (unchanged)</div></div>`);
    },
  },
  {
    title: "Worker failure",
    short: "Stop a worker, keep serving, repair",
    proves: "With a worker stopped, objects still download. The worker turns suspect at 9 s and dead at 15 s, and repair restores 2 copies on the others. When it comes back, reconciliation leaves exactly 2 copies of every chunk.",
    script: "scripts/demo-5-failure.sh",
    victim: true,
    async run(ctx) {
      const victim = ctx.victim;
      const bytes = randomBytes(3 * CHUNK_SIZE);
      const original = await sha256(bytes);
      const o = await ctx.step("Upload a 3-chunk object and wait until everything is replicated", async () => {
        ctx.object = await upload(bytes, "failure-demo.bin");
        await waitFor(ctx, 60, fullyReplicated);
        return `Stored as <span class="mono">${esc(ctx.object.object_id)}</span>; every chunk has 2 copies`;
      }).then(() => ctx.object);
      await ctx.step(`Stop ${victim} (docker stop ${victim})`, async () => {
        if (state.control) {
          await control("stop", { worker: victim });
        } else {
          await ctx.manual(`docker stop ${victim}`, "Run this in a terminal next to the cluster.", async () => (await workerState(victim)) === "SUSPECT");
        }
        ctx.stoppedAt = Date.now();
        return `${victim}'s container is stopped; it sends no more heartbeats`;
      });
      await ctx.step("Download the object while the worker is down", async () => {
        const d = await download(o.object_id);
        check(d.sha === original, "the download differs from the original");
        return `Correct bytes, SHA-256 <span class="mono">${short(d.sha)}</span>, served from the remaining copies`;
      });
      await ctx.step(`Wait for ${victim} to turn suspect (no heartbeat for 9 s)`, async () => {
        await waitFor(ctx, 40, async () => ["SUSPECT", "DEAD"].includes(await workerState(victim)));
        const r = await api("/internal/replication");
        return `Suspect ${((Date.now() - ctx.stoppedAt) / 1000).toFixed(0)} s after the stop; ${r.under_replicated} chunks already count as under-replicated`;
      });
      await ctx.step(`Wait for ${victim} to be declared dead (no heartbeat for 15 s)`, async () => {
        await waitFor(ctx, 40, async () => (await workerState(victim)) === "DEAD");
        return `Dead ${((Date.now() - ctx.stoppedAt) / 1000).toFixed(0)} s after the stop; its copies are now marked lost`;
      });
      await ctx.step("Wait for repair to restore 2 copies of every chunk", async () => {
        const s = await waitFor(ctx, 120, fullyReplicated);
        const expected = await api(`/internal/workers/${victim}/chunks?view=expected`);
        check(expected.chunks.every((c) => c.status !== "HEALTHY"), `a healthy copy is still counted on ${victim}`);
        return `Fully replicated again after ${s} s more, using only the other three workers`;
      });
      await ctx.step(`Start ${victim} again (docker start ${victim})`, async () => {
        if (state.control) {
          await control("start", { worker: victim });
        } else {
          await ctx.manual(`docker start ${victim}`, "Run this in the terminal to bring the worker back.", async () => (await workerState(victim)) === "ALIVE");
        }
        await waitFor(ctx, 60, async () => (await workerState(victim)) === "ALIVE");
        return `${victim} registered again and is alive`;
      });
      await ctx.step("Wait for reconciliation: exactly 2 copies of every chunk on disk", async () => {
        await waitFor(ctx, 120, async () => {
          const r = await api("/internal/replication");
          return r.under_replicated === 0 && r.unavailable === 0 && r.pending_tombstones === 0 && (await liveCopies()) === 2 * r.total_chunks;
        });
        const r = await api("/internal/replication");
        return `${(2 * r.total_chunks).toLocaleString()} files on disk for ${r.total_chunks.toLocaleString()} chunks; extra copies on ${victim} were removed`;
      });
      await ctx.step("Download the object once more", async () => {
        const d = await download(o.object_id);
        check(d.sha === original, "the download differs from the original");
        return "Still byte for byte identical";
      });
    },
  },
  {
    title: "Integrity",
    short: "Corrupt a copy, never serve it",
    proves: "A copy is corrupted inside a worker's volume. The download still returns the right bytes, the logs name the chunk and the worker, and repair rewrites the bad copy so there are 2 valid copies again.",
    script: "scripts/demo-6-integrity.sh",
    async run(ctx) {
      const bytes = randomBytes(3 * CHUNK_SIZE);
      const original = await sha256(bytes);
      const o = await ctx.step("Upload a 3-chunk object and wait until everything is replicated", async () => {
        ctx.object = await upload(bytes, "integrity-demo.bin");
        await waitFor(ctx, 60, fullyReplicated);
        return `Stored as <span class="mono">${esc(ctx.object.object_id)}</span>`;
      }).then(() => ctx.object);
      const meta = await metadataOf(o.object_id);
      const chunk = meta.chunks[1];
      // A download reads copies in worker order, so corrupt the copy it will read first.
      const worker = chunk.replicas.map((r) => r.worker_id).sort()[0];
      const before = chunk.replicas.find((r) => r.worker_id === worker).last_verified_at;
      await ctx.step(`Corrupt chunk 1's copy on ${worker}`, async () => {
        if (state.control) {
          const result = await control("corrupt", { worker, chunk: chunk.chunk_id });
          ctx.corruptedPath = result.path;
        } else {
          await ctx.manual(`scripts/corrupt-chunk.sh ${worker} ${chunk.chunk_id}`, "Run this from the repository folder; it flips one bit in that file.");
        }
        return `Flipped one bit of <span class="mono">${esc(ctx.corruptedPath || chunk.chunk_id)}</span> on ${worker}`;
      });
      await ctx.step("Download the object", async () => {
        const d = await download(o.object_id);
        check(d.sha === original, "corrupted bytes reached the client");
        return `Correct bytes: the worker found its copy corrupt, and the controller read the other copy`;
      });
      await ctx.step("Show the detection in the logs", async () => {
        if (!state.control) {
          return `Look for <span class="mono">${esc(chunk.chunk_id)}</span> in <span class="mono">docker compose logs controller ${worker}</span>`;
        }
        await sleep(1000);
        const [w, c] = await Promise.all([control("logs", { worker, chunk: chunk.chunk_id }, "GET"), control("logs", { worker: "controller", chunk: chunk.chunk_id }, "GET")]);
        const lines = [...w.lines.map((l) => [worker, l]), ...c.lines.map((l) => ["controller", l])].map(([who, line]) => {
          try { const j = JSON.parse(line); return `<div><span>${esc(who)}</span>${esc(j.log.level)} ${esc(j.message)}</div>`; } catch (e) { return ""; }
        }).filter(Boolean);
        check(lines.length > 0, "no log line names the chunk");
        ctx.evidence(`<h3>Log lines naming the chunk</h3><div class="hashes">${lines.join("")}</div>`);
        return `${lines.length} log lines name the chunk and ${worker}`;
      });
      await ctx.step(`Wait for repair to rewrite the copy on ${worker}`, async () => {
        await waitFor(ctx, 90, async () => {
          const m = await metadataOf(o.object_id);
          const copy = m.chunks[1].replicas.find((r) => r.worker_id === worker);
          if (!copy || copy.status !== "HEALTHY" || copy.last_verified_at <= before) return false;
          const inv = await api(`/internal/workers/${worker}/chunks`);
          return inv.chunks.some((x) => x.chunk_id === chunk.chunk_id) && !inv.quarantined.includes(chunk.chunk_id);
        });
        return `${worker} serves a freshly verified copy again, and the quarantined file is gone`;
      });
      await ctx.step("Check the cluster is fully replicated and the object is intact", async () => {
        await waitFor(ctx, 60, fullyReplicated);
        const d = await download(o.object_id);
        check(d.sha === original, "the download differs from the original");
        return "2 valid copies of every chunk; the download is byte for byte identical";
      });
    },
  },
];

// Rendering

function renderList(active) {
  $("demo-list").innerHTML = DEMOS.map((d, i) => `
    <li><button type="button" role="tab" aria-selected="${i === active}" data-demo="${i}">
      <span class="num">${i + 1}</span>
      <span class="name">${esc(d.title)}<small>${esc(d.short)}</small></span>
      <span class="result ${results[i] || ""}">${results[i] === "pass" ? "Passed" : results[i] === "fail" ? "Failed" : ""}</span>
    </button></li>`).join("");
}

let active = 0;
function showDemo(i) {
  if (running) return;
  active = i;
  renderList(i);
  const d = DEMOS[i];
  const needsControl = i >= 4;
  $("demo-panel").innerHTML = `
    <header>
      <div><h3>${i + 1}. ${esc(d.title)}</h3><p>${esc(d.proves)}</p></div>
      <div class="demo-controls">
        ${d.victim ? `<label class="visually-hidden" for="victim">Worker to stop</label>
          <select id="victim">${WORKERS.map((w) => `<option ${w === "worker2" ? "selected" : ""}>${w}</option>`).join("")}</select>` : ""}
        <button type="button" class="primary" id="run-demo">Run demonstration ${i + 1}</button>
      </div>
    </header>
    ${needsControl && !state.control ? `<p class="notice">For one-click Docker actions, start the demo control first:
      <code>docker compose --profile demo up -d</code>. Without it, this demo shows each command for you to run in a terminal.</p>` : ""}
    <ol class="steps" id="demo-steps"></ol>
    <div class="evidence" id="demo-evidence"></div>
    <p class="muted">Same checks as <code>${esc(d.script)}</code>.</p>`;
  $("run-demo").onclick = () => runDemo(i);
}

async function runDemo(i) {
  if (running) return;
  running = true;
  const d = DEMOS[i];
  const ctx = makeContext();
  ctx.victim = $("victim") ? $("victim").value : "worker2";
  $("run-demo").disabled = true;
  $("demo-list").querySelectorAll("button").forEach((b) => { b.disabled = true; });
  $("demo-steps").innerHTML = "";
  $("demo-evidence").innerHTML = "";
  log(`Started demonstration ${i + 1}: ${d.title}`);
  try {
    await d.run(ctx);
    results[i] = "pass";
    ctx.verdict(true, `Demonstration ${i + 1} passed: ${d.short.toLowerCase()}.`);
    log(`Demonstration ${i + 1} passed`, "ok");
    toast(`Demonstration ${i + 1} passed`);
  } catch (e) {
    results[i] = "fail";
    ctx.verdict(false, `Demonstration ${i + 1} failed: ${e.message}`);
    log(`Demonstration ${i + 1} failed: ${e.message}`, "err");
    toast(`Demonstration ${i + 1} failed`, "err");
  } finally {
    running = false;
    $("run-demo").disabled = false;
    renderList(active);
    refreshObjects();
  }
}

$("demo-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-demo]");
  if (button) showDemo(Number(button.dataset.demo));
});
$("demo-list").addEventListener("keydown", (event) => {
  if (!["ArrowDown", "ArrowUp"].includes(event.key) || running) return;
  event.preventDefault();
  const next = (active + (event.key === "ArrowDown" ? 1 : DEMOS.length - 1)) % DEMOS.length;
  showDemo(next);
  $("demo-list").querySelector(`[data-demo="${next}"]`).focus();
});

showDemo(0);
// Refresh the notice about demo control once its status is known.
setTimeout(() => { if (!running) showDemo(active); }, 1500);

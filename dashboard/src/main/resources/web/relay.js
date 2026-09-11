/* Dashboard behaviour: fetch, render, and the selection that ties the views together.
 *
 * The organising idea is that there is one selection, shared. Clicking a player traces
 * them through the topology and fills the trace panel; clicking a backend or pool in
 * the topology narrows the player list to the people on it. Two views showing the same
 * thing from different angles beats two views that happen to sit on the same page. */

import { buildGraph, render as renderTopology, traceOf } from "./topology.js";
import { chartCard, formatBytes, formatCount } from "./charts.js";

const $ = id => document.getElementById(id);

/** Rows per request. The proxy caps this at 200; nobody reads more at once anyway. */
const PAGE_SIZE = 50;

const emptyPage = () =>
  ({ total: 0, matched: 0, offset: 0, limit: PAGE_SIZE, players: [], edges: [] });

let data = { overview: null, servers: [], groups: [], page: emptyPage(), metrics: null };
/**
 * Either {kind:"node", id:<graph node id>} or {kind:"player", id:<routeId>, player:<row>}.
 *
 * A player selection carries the row rather than looking it up, because the row may not
 * be on the page any more -- the operator can search for someone, trace them, and then
 * page away without the panel emptying underneath them.
 */
let selection = null;
/**
 * The narrowing, which the proxy applies rather than the browser.
 *
 * Filtering here meant fetching every player to throw most of them away, which is the
 * thing that stopped scaling: the whole list, each row carrying a freshly walked route,
 * rebuilt every five seconds for every open tab.
 */
let search = "";
let serverFilter = "";
let offset = 0;
/** What the topology was last drawn from, so an unchanged graph is left alone. */
let drawn = "";

const duration = s => {
  if (s < 60) return s + "s";
  if (s < 3600) return Math.floor(s / 60) + "m";
  if (s < 86400) return Math.floor(s / 3600) + "h " + Math.floor((s % 3600) / 60) + "m";
  return Math.floor(s / 86400) + "d " + Math.floor((s % 86400) / 3600) + "h";
};

// Text nodes rather than innerHTML: usernames come from whoever joined the server,
// and a player named after a <script> tag should be a curiosity in a table cell,
// not a way into this page.
const row = cells => {
  const tr = document.createElement("tr");
  for (const cell of cells) {
    const td = document.createElement("td");
    if (typeof cell === "number") td.className = "num";
    td.textContent = cell === null || cell === undefined ? "—" : String(cell);
    tr.appendChild(td);
  }
  return tr;
};

const bar = (cell, fraction, tone) => {
  if (!(fraction >= 0)) return;
  const outer = document.createElement("span");
  outer.className = "bar" + (tone ? " " + tone : "");
  const inner = document.createElement("i");
  inner.style.width = Math.max(0, Math.min(1, fraction)) * 100 + "%";
  outer.appendChild(inner);
  cell.appendChild(outer);
};

const fill = (tbody, rows, columns, emptyText) => {
  tbody.replaceChildren();
  if (!rows.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = columns;
    td.className = "empty";
    td.textContent = emptyText;
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }
  for (const r of rows) tbody.appendChild(r);
};

/** The player a "player" selection refers to, or null if nobody is traced. */
const selectedPlayer = () =>
  selection && selection.kind === "player" ? selection.player : null;

/** Does this player's route pass through the selected graph node? */
function routesThrough(player, nodeId) {
  const [kind, name] = [nodeId.slice(0, nodeId.indexOf(":")), nodeId.slice(nodeId.indexOf(":") + 1)];
  return (player.route || []).some(hop =>
    (kind === "edge" && hop.kind === "EDGE" && hop.name === name)
    || (kind === "proxy" && hop.kind === "PROXY" && hop.name === name)
    || (kind === "pool" && hop.kind === "POOL" && hop.name === name)
    || (kind === "backend" && hop.kind === "BACKEND" && hop.name === name));
}

/** The name a node selection narrows the player list to, or "" for no narrowing. */
const nodeFilter = sel => {
  if (!sel || sel.kind !== "node") return "";
  const cut = sel.id.indexOf(":");
  const kind = sel.id.slice(0, cut);
  // A pool or a backend is a set of players the proxy can look up. The proxy node is
  // everyone, and an edge is not a backend, so neither narrows anything.
  return kind === "backend" || kind === "pool" ? sel.id.slice(cut + 1) : "";
};

/** How many players are on a graph node, from whichever collection knows exactly. */
const countOn = (kind, name) => {
  if (kind === "backend") return (data.servers.find(s => s.name === name) || {}).players || 0;
  if (kind === "pool") return (data.groups.find(g => g.name === name) || {}).players || 0;
  if (kind === "edge") return (data.page.edges.find(e => e.name === name) || {}).players || 0;
  return data.overview ? data.overview.players : 0;
};

function select(next) {
  // Clicking the current selection again clears it, so there is always a way out
  // that does not require finding the button.
  selection = selection && next && selection.kind === next.kind && selection.id === next.id
    ? null : next;

  // Picking a backend or a pool asks the proxy for its players rather than dimming
  // whichever of them happened to be on the page. Picking a *player* leaves the
  // narrowing alone: tracing someone you found by filtering should not undo the filter.
  if (!next || next.kind === "node") {
    const want = nodeFilter(selection);
    if (want !== serverFilter) {
      serverFilter = want;
      offset = 0;
      refresh();
      return;
    }
  }
  paint();
}

// ------------------------------------------------------------------ rendering

function paint() {
  const { overview } = data;
  if (!overview) return;

  $("meta").textContent =
    overview.node + " · " + overview.version + " · listening on " + overview.bind
    + " · up " + duration(overview.uptimeSeconds);

  $("tiles").replaceChildren(...[
    ["Players", overview.players + " / " + overview.maxPlayers],
    ["Backends up", overview.serversUp + " / " + overview.servers],
    ["Groups", overview.groups],
    ["Balancing", overview.balance],
    ["Uptime", duration(overview.uptimeSeconds)],
  ].map(([k, n]) => {
    const d = document.createElement("div");
    d.className = "tile";
    const nv = document.createElement("div");
    nv.className = "n";
    nv.textContent = n;
    const kv = document.createElement("div");
    kv.className = "k";
    kv.textContent = k;
    d.append(nv, kv);
    return d;
  }));

  paintMetrics();
  paintTopology();
  paintTrace();
  paintServers();
  paintPlayers();
}

/**
 * The §10 counters, as four sparklines and a line of totals.
 *
 * Traffic in and out share a scale so the two shapes can be compared; everything else
 * is scaled to itself, because a switch rate and a byte rate have nothing to say to
 * each other.
 */
function paintMetrics() {
  const m = data.metrics;
  const wrap = $("charts");
  if (!m || !m.history || m.history.length < 2) {
    wrap.replaceChildren(Object.assign(document.createElement("div"), {
      className: "empty",
      textContent: m ? "collecting — the first readings arrive within a few seconds"
                     : "no metrics from the proxy",
    }));
    $("counters").textContent = "";
    return;
  }

  const h = m.history;
  const last = h[h.length - 1];
  const peakTraffic = Math.max(...h.map(s => Math.max(s.bytesIn, s.bytesOut)));
  const window = Math.round(h.length * m.sampleMillis / 60000);

  wrap.replaceChildren(
    chartCard({ label: "Players", reading: String(last.players),
                sub: "last " + window + " min", values: h.map(s => s.players) }),
    chartCard({ label: "To players", reading: formatBytes(last.bytesOut) + "/s",
                sub: "peak " + formatBytes(peakTraffic) + "/s", tone: "alt",
                values: h.map(s => s.bytesOut), ceiling: peakTraffic }),
    chartCard({ label: "From players", reading: formatBytes(last.bytesIn) + "/s",
                sub: "shared scale with outbound",
                values: h.map(s => s.bytesIn), ceiling: peakTraffic }),
    chartCard({ label: "Connections", reading: String(m.connectionsActive),
                sub: last.connects.toFixed(2) + "/s new",
                values: h.map(s => s.connections) }));

  // Totals rather than rates: these are the numbers that only make sense cumulatively,
  // and a sparkline of a monotonic counter is a diagonal line saying nothing.
  const bits = [
    formatCount(m.connectionsTotal) + " connections",
    formatCount(m.switches) + " switches",
    formatCount(m.failovers) + " failovers",
    formatCount(m.routeDecisions) + " routing decisions",
  ];
  if (m.routeFailures > 0) bits.push(formatCount(m.routeFailures) + " routing failures");
  if (m.connectionsFailed > 0) bits.push(formatCount(m.connectionsFailed) + " failed");
  // Both sides, because the player side is what an uplink is billed for and the
  // backend side is what a switch storm actually costs.
  bits.push("players " + formatBytes(m.playerBytesIn) + " up / "
            + formatBytes(m.playerBytesOut) + " down");
  bits.push("backends " + formatBytes(m.backendBytesIn) + " in / "
            + formatBytes(m.backendBytesOut) + " out");
  $("counters").textContent = "since start — " + bits.join(" · ");
}

function paintTopology() {
  const graph = buildGraph(data.overview, data.servers, data.groups, data.page.edges);
  const player = selectedPlayer();
  const trace = player ? traceOf(player, data.overview) : null;
  const picked = selection && selection.kind === "node" ? selection.id : null;

  // Redrawing resets the route animation, so an unchanged graph is left standing.
  // At a five-second refresh a restarting dot is the difference between a page that
  // feels alive and one that feels like it is flickering.
  const signature = JSON.stringify([
    graph.nodes.map(n => [n.id, n.title, n.sub, n.health, n.badge, n.warn,
                          n.load >= 0 ? Math.round(n.load * 100) : -1]),
    graph.links.map(l => [l.from, l.to, l.weight]),
    trace ? [...trace.nodes] : null, picked,
  ]);
  if (signature === drawn) return;
  drawn = signature;

  renderTopology($("net"), graph, trace,
    id => select({ kind: "node", id }), picked);
}

/**
 * Past sessions for the traced player (§9.1 connection history).
 *
 * <p>Fetched rather than pushed, and only for the player being looked at: the history
 * of everyone who has ever connected is a table nobody reads, while "where has this
 * person been" is the question the trace panel is already asking.
 */
async function paintHistory(uuid, routeId) {
  const host = $("past");
  host.replaceChildren();
  if (!uuid) return;

  let sessions;
  try {
    sessions = await fetch("api/history?limit=6&uuid=" + encodeURIComponent(uuid))
      .then(r => r.json());
  } catch (e) {
    return;                       // storage may be off; the live route still stands
  }
  // The selection may have moved on while this was in flight.
  const current = selectedPlayer();
  if (!current || current.routeId !== routeId) return;

  const earlier = sessions.filter(s => s.routeId !== routeId);
  if (!earlier.length) return;

  const title = document.createElement("h4");
  title.textContent = "Earlier visits";
  host.appendChild(title);

  for (const session of earlier) {
    const div = document.createElement("div");
    div.className = "sess";
    const when = document.createElement("div");
    when.className = "when";
    const ended = session.disconnectedAt;
    when.textContent = new Date(session.connectedAt).toLocaleString()
      + (ended ? " · " + duration(Math.round((ended - session.connectedAt) / 1000)) : " · ongoing");
    div.appendChild(when);

    const path = document.createElement("div");
    path.className = "path";
    session.visits.forEach((visit, i) => {
      if (i > 0) {
        const sep = document.createElement("span");
        sep.className = "sep";
        sep.textContent = "→";
        path.appendChild(sep);
      }
      const stop = document.createElement("span");
      stop.className = "stop";
      stop.textContent = visit.server;
      if (visit.leftAt) {
        const held = document.createElement("em");
        held.textContent = duration(Math.round((visit.leftAt - visit.joinedAt) / 1000));
        stop.appendChild(held);
      }
      path.appendChild(stop);
    });
    div.appendChild(path);
    host.appendChild(div);
  }
}

function paintTrace() {
  const panel = $("trace");
  const player = selectedPlayer();

  $("past").replaceChildren();
  if (selection && selection.kind === "node") {
    const cut = selection.id.indexOf(":");
    const name = selection.id.slice(cut + 1);
    // Counted from servers, groups and the page's edge totals rather than by scanning
    // the rows on screen, which would now say "50" about every busy backend.
    const on = countOn(selection.id.slice(0, cut), name);
    panel.classList.add("on");
    $("traceTitle").textContent = name;
    $("traceMeta").textContent = on === 1
      ? "1 player is routed through here" : on + " players are routed through here";
    $("hops").replaceChildren();
    return;
  }

  if (!player) {
    panel.classList.remove("on");
    return;
  }

  panel.classList.add("on");
  $("traceTitle").textContent = player.username;
  $("traceMeta").replaceChildren(
    document.createTextNode(
      "protocol " + player.protocol + " · online " + duration(player.onlineSeconds)
      + (player.virtualHost ? " · connected to " + player.virtualHost : "")
      + " · route "),
    Object.assign(document.createElement("code"), { textContent: player.routeId }));

  // The vertical chain the spec draws: one hop per line, in order.
  $("hops").replaceChildren(...(player.route || []).map(hop => {
    const line = document.createElement("div");
    line.className = "hop k-" + hop.kind;
    const kind = document.createElement("span");
    kind.className = "kind";
    kind.textContent = hop.kind.toLowerCase();
    const body = document.createElement("span");
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = hop.name;
    body.appendChild(name);
    if (hop.detail) {
      const detail = document.createElement("span");
      detail.className = "detail";
      detail.textContent = hop.detail;
      body.appendChild(detail);
    }
    line.append(kind, body);
    return line;
  }));

  if (!(player.route || []).length) {
    const none = document.createElement("div");
    none.className = "empty";
    none.textContent = "connecting — no route yet";
    $("hops").replaceChildren(none);
  }

  paintHistory(player.uuid, player.routeId);
}

function paintServers() {
  const picked = selection && selection.kind === "node" ? selection.id : null;

  fill($("servers"), data.servers.map(s => {
    const load = s.load;
    const tr = row([s.name, "", s.group, s.players,
                    load && load.tps1m >= 0 ? load.tps1m.toFixed(1) : null,
                    load && load.msptMean >= 0 ? load.msptMean.toFixed(1) + "ms" : null,
                    load && load.memoryMaxMb > 0
                      ? load.memoryUsedMb + " / " + load.memoryMaxMb + " MB" : null,
                    load && load.cpuLoad >= 0 ? Math.round(load.cpuLoad * 100) + "%" : null,
                    s.latencyMillis < 0 ? null : s.latencyMillis + "ms",
                    s.address]);
    tr.classList.add("pick");
    if (picked === "backend:" + s.name) tr.classList.add("on");
    tr.addEventListener("click", () => select({ kind: "node", id: "backend:" + s.name }));

    const cell = tr.children[1];
    const pill = document.createElement("span");
    pill.className = "pill " + s.status;
    pill.textContent = s.status.toLowerCase();
    cell.replaceChildren(pill);
    if (s.detail) {
      const why = document.createElement("span");
      why.className = "why";
      why.textContent = s.detail;
      cell.appendChild(why);
    }

    if (load) {
      bar(tr.children[4], load.tps1m / 20, load.tps1m >= 19.5 ? "" : load.tps1m >= 18 ? "warn" : "hot");
      bar(tr.children[5], Math.min(load.msptMean / 50, 1),
          load.msptMean < 25 ? "" : load.msptMean < 45 ? "warn" : "hot");
      if (load.memoryMaxMb > 0) {
        const used = load.memoryUsedMb / load.memoryMaxMb;
        bar(tr.children[6], used, used < 0.75 ? "" : used < 0.9 ? "warn" : "hot");
      }
      // Greyed rather than hidden when stale. A backend can only report while a
      // player is on it, so these are usually last-known rather than wrong -- and
      // hiding them would lose the only clue that a server froze.
      if (load.stale) {
        for (let i = 4; i <= 7; i++) tr.children[i].classList.add("stale");
        tr.children[4].title = "last reported " + duration(load.ageSeconds) + " ago";
      }
    }
    return tr;
  }), 10, "no backends configured");
}

function paintPlayers() {
  const page = data.page;
  // Only a selection the proxy is not already narrowing by still dims rows: after a
  // backend click every row on the page matches, and dimming none of them says nothing.
  const picked = selection && selection.kind === "node" && !nodeFilter(selection)
    ? selection.id : null;
  const player = selectedPlayer();

  $("playerCount").textContent = describePage(page);

  fill($("players"), page.players.map(p => {
    const tr = row([p.username, p.server, p.virtualHost, p.protocol,
                    duration(p.onlineSeconds), p.routeId]);
    tr.classList.add("pick");
    tr.children[5].className = "rid";
    if (player && player.routeId === p.routeId) tr.classList.add("on");
    if (picked && !routesThrough(p, picked)) tr.classList.add("dim");
    tr.addEventListener("click", () => select({ kind: "player", id: p.routeId, player: p }));
    return tr;
  }), 6, emptyPlayersText());
  paintPager(page);
}

const narrowed = () => !!(search || serverFilter);

function emptyPlayersText() {
  if (search) return "nobody matches “" + search + "”";
  if (serverFilter) return "nobody is on " + serverFilter;
  return "nobody online";
}

/** "1,203 online", or which slice of what this is once it stops fitting. */
function describePage(page) {
  const n = narrowed() ? page.matched : page.total;
  const noun = narrowed() ? " matching" : " online";
  const whole = page.offset === 0 && page.players.length >= n;
  const range = whole ? "" : (page.offset + 1) + "–" + (page.offset + page.players.length) + " of ";
  return range + formatCount(n) + noun
    + (serverFilter ? " on " + serverFilter : "")
    + (narrowed() ? " · " + formatCount(page.total) + " online" : "");
}

/**
 * Previous and next, and only when there is somewhere to go.
 *
 * Offset paging rather than a cursor, because the proxy orders by name and a name is
 * stable: a page boundary means the same thing on the next request even as people come
 * and go. Someone joining shifts a row across a boundary at worst.
 */
function paintPager(page) {
  const host = $("pager");
  host.replaceChildren();
  const n = narrowed() ? page.matched : page.total;
  const last = page.offset + page.players.length;
  if (page.offset === 0 && last >= n) return;

  const step = (label, to, enabled) => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.disabled = !enabled;
    b.addEventListener("click", () => { offset = to; refresh(); });
    return b;
  };
  host.append(
    step("‹ prev", Math.max(0, page.offset - page.limit), page.offset > 0),
    step("next ›", page.offset + page.limit, last < n));
}

// --------------------------------------------------------------------- plumbing

async function refresh() {
  const query = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
  if (search) query.set("q", search);
  if (serverFilter) query.set("server", serverFilter);
  try {
    const [overview, servers, groups, page, metrics] = await Promise.all([
      ...["overview", "servers", "groups"].map(p => fetch("api/" + p).then(r => r.json())),
      fetch("api/players?" + query).then(r => r.json()),
      fetch("api/metrics").then(r => r.json()),
    ]);
    data = { overview, servers, groups, page, metrics };
    // Keep the traced player's row current while they are on the page. When they are
    // not, the last-known route stands: "not in this fifty rows" is not "gone", and
    // the disconnect event below is what actually says they left.
    if (selection && selection.kind === "player") {
      const fresh = page.players.find(p => p.routeId === selection.id);
      if (fresh) selection.player = fresh;
    }
    paint();
  } catch (e) {
    $("meta").textContent = "cannot reach the proxy";
  }
}

/**
 * A refresh soon, rather than one per event.
 *
 * Every join, leave and switch used to trigger five immediate fetches. On a quiet test
 * network that is responsive; on a busy one it is a load generator pointed at the proxy
 * it is reporting on, and the answers arrive faster than anyone can read them.
 */
let pendingRefresh = null;
function refreshSoon() {
  if (pendingRefresh) return;
  pendingRefresh = setTimeout(() => { pendingRefresh = null; refresh(); }, 400);
}

// ---------------------------------------------------------------------- console

/**
 * Relay's own log, live (§9.1).
 *
 * <p>Held as data and re-rendered on filter changes rather than appended straight to the
 * DOM, because a filter that only applies to lines arriving after you set it is a filter
 * nobody trusts. The cost is a redraw per line, which at this volume is nothing.
 */
const LEVELS = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 };

/**
 * How many raw lines are held before the oldest is dropped.
 *
 * Filtering happens here rather than at the proxy, which means a noisy DEBUG stream
 * competes for this buffer with the INFO lines someone is actually reading: set the
 * filter to "info and up" under load and the visible count *falls* as debug lines push
 * the interesting ones out. Two thousand is enough that the effect stops mattering at
 * any sane level, and it is a few hundred kilobytes. A proxy running at DEBUG under
 * real traffic still turns this into a short window -- the log file is the long record,
 * and always was.
 */
const LOG_LIMIT = 2000;
let logLines = [];

const clock = ms => new Date(ms).toLocaleTimeString([], { hour12: false });

function logPasses(line) {
  const min = LEVELS[$("logLevel").value] ?? 0;
  if ((LEVELS[line.level] ?? 0) < min) return false;
  const q = $("logSearch").value.trim().toLowerCase();
  if (!q) return true;
  return (line.message + " " + line.logger + " " + line.thread).toLowerCase().includes(q);
}

function paintConsole() {
  const host = $("console");
  const shown = logLines.filter(logPasses);
  $("logCount").textContent = shown.length === logLines.length
    ? logLines.length + " lines"
    : shown.length + " of " + logLines.length;

  if (!shown.length) {
    const none = document.createElement("div");
    none.className = "empty";
    none.textContent = logLines.length ? "nothing matches this filter" : "waiting for the proxy";
    host.replaceChildren(none);
    return;
  }

  // Sticking to the bottom is only correct while the reader is already there. Scrolling
  // up to read something and being yanked back by the next line is the single most
  // annoying thing a live log can do.
  const atBottom = host.scrollHeight - host.scrollTop - host.clientHeight < 40;

  host.replaceChildren(...shown.map(line => {
    const div = document.createElement("div");
    div.className = "ln " + line.level;
    for (const [cls, text] of [["at", clock(line.at)], ["lv", line.level],
                               ["lg", line.logger], ["ms", line.message]]) {
      const span = document.createElement("span");
      span.className = cls;
      span.textContent = text;
      div.appendChild(span);
    }
    div.title = line.thread;
    return div;
  }));

  if ($("logFollow").checked && atBottom) host.scrollTop = host.scrollHeight;
}

function addLogLine(line) {
  logLines.push(line);
  if (logLines.length > LOG_LIMIT) logLines = logLines.slice(-LOG_LIMIT);
  paintConsole();
}

async function loadLog() {
  try {
    const lines = await fetch("api/log").then(r => r.json());
    logLines = (lines || []).slice(-LOG_LIMIT);
    paintConsole();
  } catch (e) {
    // The page already reports the connection; a second complaint here says nothing.
  }
}

function log(text, who) {
  const feed = $("feed");
  if (feed.firstChild && feed.firstChild.className === "empty") feed.replaceChildren();
  const line = document.createElement("div");
  const time = document.createElement("span");
  time.className = "t";
  time.textContent = new Date().toLocaleTimeString();
  const name = document.createElement("span");
  name.className = "who";
  name.textContent = who ?? "";
  line.append(time, name, document.createTextNode(who ? " " + text : text));
  feed.prepend(line);
  while (feed.childElementCount > 200) feed.lastChild.remove();
}

// The socket is what makes this live; the poll below is a safety net for the state
// it cannot describe (player counts drifting, uptime ticking).
function connect() {
  const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://")
    + location.host + "/api/events");

  ws.onopen = () => {
    $("link").classList.remove("down");
    // Re-read the tail on every reconnect: whatever happened while the socket was down
    // is exactly the part worth seeing, and the proxy kept it.
    loadLog();
  };
  ws.onclose = () => {
    $("link").classList.add("down");
    setTimeout(connect, 3000);
  };
  ws.onmessage = e => {
    const ev = JSON.parse(e.data);
    if (ev.type === "log") {
      addLogLine(ev);
      return;
    }
    const who = ev.player ? ev.player.username : ev.server ?? "someone";
    // `type` is the envelope and is always the literal "event"; `kind` is what
    // happened. Reading the wrong one made every line in this feed say "event",
    // which is a thing nobody notices because the feed still scrolls.
    const kind = ev.kind;
    // The trace panel holds its own copy of the traced player, so this is what clears
    // it. Nothing else can: a player being absent from a page is not a player leaving.
    if (kind === "PLAYER_DISCONNECTED" && selection && selection.kind === "player"
        && ev.player && ev.player.routeId === selection.id) {
      selection = null;
    }
    if (kind === "PLAYER_CONNECTED") log("joined on " + ev.to, who);
    else if (kind === "PLAYER_DISCONNECTED") log("left" + (ev.from ? " from " + ev.from : ""), who);
    else if (kind === "PLAYER_SWITCHED_SERVER") log("moved " + ev.from + " → " + ev.to, who);
    else if (kind === "SERVER_HEALTH_CHANGED") {
      // The reason is folded into `to` by the proxy, as "HEALTHY (3 in a row)".
      log("is " + ev.to.toLowerCase() + " (was " + String(ev.from).toLowerCase() + ")", ev.server);
    } else log(kind || "event", who);
    refreshSoon();
  };
}

// Debounced, because each keystroke is now a question for the proxy rather than a
// filter over an array already in the page.
let typing = null;
$("search").addEventListener("input", e => {
  search = e.target.value.trim();
  offset = 0;
  clearTimeout(typing);
  typing = setTimeout(refresh, 200);
});
$("logLevel").addEventListener("change", paintConsole);
$("logSearch").addEventListener("input", paintConsole);
$("logFollow").addEventListener("change", () => {
  if ($("logFollow").checked) $("console").scrollTop = $("console").scrollHeight;
});
$("clear").addEventListener("click", () => select(null));
document.addEventListener("keydown", e => {
  if (e.key === "Escape") select(null);
  if (e.key === "/" && document.activeElement !== $("search")) {
    e.preventDefault();
    $("search").focus();
  }
});

/**
 * Who is signed in, if anyone.
 *
 * Also where the CSRF token comes from. It is deliberately not in a cookie: a token the
 * browser attaches automatically is a token an attacker's page gets attached for them,
 * which is the thing being defended against.
 */
async function whoAmI() {
  try {
    const me = await fetch("api/me").then(r => r.json());
    const host = $("who");
    if (!me.authenticated) {
      host.textContent = me.required ? "" : "open — loopback only, no accounts configured";
      return;
    }
    host.replaceChildren();
    const name = document.createElement("b");
    name.textContent = me.username;
    host.append(name, document.createTextNode(" · " + me.role));
    const form = document.createElement("form");
    form.method = "post";
    form.action = "logout";
    const out = document.createElement("button");
    out.type = "submit";
    out.textContent = "sign out";
    form.appendChild(out);
    host.appendChild(form);
  } catch (e) {
    // The page reports the proxy connection separately; this is not the place to
    // duplicate that complaint.
  }
}

refresh();
whoAmI();
loadLog();
setInterval(refresh, 5000);
connect();

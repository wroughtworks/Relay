/* Dashboard behaviour: fetch, render, and the selection that ties the views together.
 *
 * The organising idea is that there is one selection, shared. Clicking a player traces
 * them through the topology and fills the trace panel; clicking a backend or pool in
 * the topology narrows the player list to the people on it. Two views showing the same
 * thing from different angles beats two views that happen to sit on the same page. */

import { buildGraph, render as renderTopology, traceOf } from "./topology.js";

const $ = id => document.getElementById(id);

let data = { overview: null, servers: [], groups: [], players: [] };
/** Either {kind:"player", id:<routeId>} or {kind:"node", id:<graph node id>}. */
let selection = null;
let search = "";
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

/** The player a "player" selection refers to, or null if they have since left. */
const selectedPlayer = () =>
  selection && selection.kind === "player"
    ? data.players.find(p => p.routeId === selection.id) || null
    : null;

/** Does this player's route pass through the selected graph node? */
function routesThrough(player, nodeId) {
  const [kind, name] = [nodeId.slice(0, nodeId.indexOf(":")), nodeId.slice(nodeId.indexOf(":") + 1)];
  return (player.route || []).some(hop =>
    (kind === "edge" && hop.kind === "EDGE" && hop.name === name)
    || (kind === "proxy" && hop.kind === "PROXY" && hop.name === name)
    || (kind === "pool" && hop.kind === "POOL" && hop.name === name)
    || (kind === "backend" && hop.kind === "BACKEND" && hop.name === name));
}

const matchesSearch = p => {
  if (!search) return true;
  const q = search.toLowerCase();
  return [p.username, p.routeId, p.server, p.virtualHost]
    .some(v => v && String(v).toLowerCase().includes(q));
};

function select(next) {
  // Clicking the current selection again clears it, so there is always a way out
  // that does not require finding the button.
  selection = selection && next && selection.kind === next.kind && selection.id === next.id
    ? null : next;
  paint();
}

// ------------------------------------------------------------------ rendering

function paint() {
  const { overview, servers, groups, players } = data;
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

  paintTopology();
  paintTrace();
  paintServers();
  paintPlayers();
}

function paintTopology() {
  const graph = buildGraph(data.overview, data.servers, data.groups, data.players);
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

function paintTrace() {
  const panel = $("trace");
  const player = selectedPlayer();

  if (selection && selection.kind === "node") {
    const name = selection.id.slice(selection.id.indexOf(":") + 1);
    const on = data.players.filter(p => routesThrough(p, selection.id)).length;
    panel.classList.add("on");
    $("traceTitle").textContent = name;
    $("traceMeta").textContent = on === 1
      ? "1 player is routed through here" : on + " players are routed through here";
    $("hops").replaceChildren();
    return;
  }

  if (!player) {
    panel.classList.remove("on");
    // A selected player who has since disconnected leaves a stale selection behind.
    if (selection && selection.kind === "player") selection = null;
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
  const picked = selection && selection.kind === "node" ? selection.id : null;
  const player = selectedPlayer();
  const shown = data.players.filter(matchesSearch);

  $("playerCount").textContent = shown.length === data.players.length
    ? data.players.length + " online"
    : shown.length + " of " + data.players.length + " shown";

  fill($("players"), shown.map(p => {
    const tr = row([p.username, p.server, p.virtualHost, p.protocol,
                    duration(p.onlineSeconds), p.routeId]);
    tr.classList.add("pick");
    tr.children[5].className = "rid";
    if (player && player.routeId === p.routeId) tr.classList.add("on");
    if (picked && !routesThrough(p, picked)) tr.classList.add("dim");
    tr.addEventListener("click", () => select({ kind: "player", id: p.routeId }));
    return tr;
  }), 6, search ? "nobody matches “" + search + "”" : "nobody online");
}

// --------------------------------------------------------------------- plumbing

async function refresh() {
  try {
    const [overview, servers, groups, players] = await Promise.all(
      ["overview", "servers", "groups", "players"]
        .map(p => fetch("api/" + p).then(r => r.json())));
    data = { overview, servers, groups, players };
    paint();
  } catch (e) {
    $("meta").textContent = "cannot reach the proxy";
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

  ws.onopen = () => $("link").classList.remove("down");
  ws.onclose = () => {
    $("link").classList.add("down");
    setTimeout(connect, 3000);
  };
  ws.onmessage = e => {
    const ev = JSON.parse(e.data);
    const who = ev.player ? ev.player.username : ev.server ?? "someone";
    // `type` is the envelope and is always the literal "event"; `kind` is what
    // happened. Reading the wrong one made every line in this feed say "event",
    // which is a thing nobody notices because the feed still scrolls.
    const kind = ev.kind;
    if (kind === "PLAYER_CONNECTED") log("joined on " + ev.to, who);
    else if (kind === "PLAYER_DISCONNECTED") log("left" + (ev.from ? " from " + ev.from : ""), who);
    else if (kind === "PLAYER_SWITCHED_SERVER") log("moved " + ev.from + " → " + ev.to, who);
    else if (kind === "SERVER_HEALTH_CHANGED") {
      // The reason is folded into `to` by the proxy, as "HEALTHY (3 in a row)".
      log("is " + ev.to.toLowerCase() + " (was " + String(ev.from).toLowerCase() + ")", ev.server);
    } else log(kind || "event", who);
    refresh();
  };
}

$("search").addEventListener("input", e => { search = e.target.value.trim(); paintPlayers(); });
$("clear").addEventListener("click", () => select(null));
document.addEventListener("keydown", e => {
  if (e.key === "Escape") select(null);
  if (e.key === "/" && document.activeElement !== $("search")) {
    e.preventDefault();
    $("search").focus();
  }
});

refresh();
setInterval(refresh, 5000);
connect();

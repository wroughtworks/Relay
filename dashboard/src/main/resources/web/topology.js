/* The network, drawn.
 *
 * Spec 9.2 asks for an interactive topology map, and for operators to be able to
 * "inspect an individual player's route through the topology". Both are here: the
 * graph is built from the same /api/servers and /api/groups the tables use, and a
 * selected player lights their own path through it.
 *
 * Hand-drawn SVG rather than a graph library. Partly because the page must render
 * with no route to the internet, but mostly because the layout is not general: this
 * is a layered network with four fixed tiers, and a force-directed engine would
 * spend its time discovering an arrangement that is already known.
 *
 * Nothing here reads the DOM back. It is given data, it produces an <svg>, and the
 * only state it keeps between renders is the pan offset -- so a refresh landing
 * mid-inspection does not move the graph under the pointer. */

const SVGNS = "http://www.w3.org/2000/svg";

const NODE_W = 172;
const NODE_H = 54;
const COL_GAP = 88;
const ROW_GAP = 18;
const PAD_X = 18;
const PAD_TOP = 30;
const PAD_BOTTOM = 14;

/** Tier order, left to right. Empty tiers collapse rather than leaving a gap. */
const TIERS = ["INTERNET", "EDGE", "PROXY", "POOL", "BACKEND"];
const TIER_LABEL = {
  INTERNET: "internet", EDGE: "edge", PROXY: "proxy", POOL: "pool", BACKEND: "backend",
};

const svgEl = (name, attrs) => {
  const node = document.createElementNS(SVGNS, name);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v !== null && v !== undefined) node.setAttribute(k, String(v));
  }
  return node;
};

/** Text is always set as a text node: backend names come from config, player names do not. */
const svgText = (attrs, content) => {
  const node = svgEl("text", attrs);
  node.textContent = content;
  return node;
};

/**
 * Turns the four API collections into nodes and links.
 *
 * Edge nodes are the interesting case: Relay only knows something sits in front of
 * it when that thing sends a PROXY protocol header, so edges exist in the graph
 * only because some player arrived through one. A network with no upstream simply
 * has no edge tier, which is the truth rather than an omission.
 */
export function buildGraph(overview, servers, groups, players) {
  const nodes = [];
  const links = [];
  const add = (node) => { nodes.push(node); return node; };

  const totalPlayers = overview ? overview.players : 0;
  add({ id: "internet", tier: "INTERNET", title: "Internet",
        sub: totalPlayers === 1 ? "1 player" : totalPlayers + " players" });

  // One edge per distinct upstream seen in a live route.
  const edgeCounts = new Map();
  for (const p of players) {
    for (const hop of p.route || []) {
      if (hop.kind === "EDGE") edgeCounts.set(hop.name, (edgeCounts.get(hop.name) || 0) + 1);
    }
  }
  for (const [name, count] of edgeCounts) {
    add({ id: "edge:" + name, tier: "EDGE", title: name, sub: count + " via here" });
    links.push({ from: "internet", to: "edge:" + name, weight: count });
    links.push({ from: "edge:" + name, to: proxyId(overview), weight: count });
  }

  const proxy = add({
    id: proxyId(overview), tier: "PROXY",
    title: overview ? overview.node : "relay",
    sub: overview ? overview.bind : "",
    load: overview && overview.maxPlayers > 0 ? overview.players / overview.maxPlayers : -1,
    badge: overview ? overview.players + " / " + overview.maxPlayers : null,
  });
  if (!edgeCounts.size) {
    links.push({ from: "internet", to: proxy.id, weight: totalPlayers });
  }

  // A backend's share of everyone online. For a proxy whose whole job is balancing,
  // that is the number worth seeing on the node itself -- capacity per backend is
  // not something Relay is told, so a "percent full" bar would be invented.
  const share = (n) => (totalPlayers > 0 ? n / totalPlayers : 0);

  const grouped = new Set();
  for (const g of groups) {
    add({ id: "pool:" + g.name, tier: "POOL", title: g.name,
          sub: g.healthy + " of " + g.members.length + " taking players",
          load: share(g.players), badge: g.players || null,
          warn: g.healthy === 0 ? "hot" : g.healthy < g.members.length ? "warm" : null });
    links.push({ from: proxy.id, to: "pool:" + g.name, weight: g.players });
    for (const member of g.members) grouped.add(member);
  }

  for (const s of servers) {
    const id = "backend:" + s.name;
    add({
      id, tier: "BACKEND", title: s.name, sub: s.address, health: s.status,
      load: share(s.players), badge: s.players || null,
      warn: tone(s),
    });
    const parent = s.group && groups.some(g => g.name === s.group)
      ? "pool:" + s.group : proxy.id;
    links.push({ from: parent, to: id, weight: s.players });
  }
  void grouped;

  return { nodes, links };
}

const proxyId = (overview) => "proxy:" + (overview ? overview.node : "relay");

/** Colour for the load bar: what an operator would actually react to. */
function tone(server) {
  if (server.status !== "HEALTHY") return "hot";
  const load = server.load;
  if (!load || load.stale || !(load.tps1m >= 0)) return null;
  if (load.tps1m < 18) return "hot";
  if (load.tps1m < 19.5) return "warm";
  return null;
}

/** Assigns every node an x/y. Tiers become columns; nodes stack within one. */
function layout(graph) {
  const used = TIERS.filter(t => graph.nodes.some(n => n.tier === t));
  const columns = new Map(used.map((t, i) => [t, i]));
  const rows = new Map(used.map(t => [t, 0]));
  const tallest = Math.max(...used.map(t => graph.nodes.filter(n => n.tier === t).length));

  for (const node of graph.nodes) {
    const col = columns.get(node.tier);
    const row = rows.get(node.tier);
    rows.set(node.tier, row + 1);
    const count = graph.nodes.filter(n => n.tier === node.tier).length;
    const columnHeight = count * NODE_H + (count - 1) * ROW_GAP;
    const fullHeight = tallest * NODE_H + (tallest - 1) * ROW_GAP;
    node.x = PAD_X + col * (NODE_W + COL_GAP);
    // Columns are centred against the tallest, so a lone proxy sits opposite the
    // middle of six backends rather than at the top of them.
    node.y = PAD_TOP + (fullHeight - columnHeight) / 2 + row * (NODE_H + ROW_GAP);
  }

  return {
    width: PAD_X * 2 + used.length * NODE_W + (used.length - 1) * COL_GAP,
    height: PAD_TOP + tallest * NODE_H + (tallest - 1) * ROW_GAP + PAD_BOTTOM,
    columns: used,
  };
}

/** A cubic curve from one node's right edge to the next node's left edge. */
function linkPath(a, b) {
  const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2;
  const x2 = b.x, y2 = b.y + NODE_H / 2;
  const bend = Math.max(24, (x2 - x1) * 0.5);
  return `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
}

/**
 * The node ids and link ids a player's route runs through.
 *
 * Built from the hops the proxy reported rather than by walking the graph, because
 * the route is a fact about that session and the graph is only how it is drawn. A
 * player mid-switch has no backend hop, and the trace should stop where they are.
 */
export function traceOf(player, overview) {
  if (!player) return null;
  const ids = ["internet"];
  for (const hop of player.route || []) {
    if (hop.kind === "EDGE") ids.push("edge:" + hop.name);
    else if (hop.kind === "PROXY") ids.push(proxyId(overview));
    else if (hop.kind === "POOL") ids.push("pool:" + hop.name);
    else if (hop.kind === "BACKEND") ids.push("backend:" + hop.name);
  }
  const links = [];
  for (let i = 1; i < ids.length; i++) links.push(ids[i - 1] + "->" + ids[i]);
  return { nodes: new Set(ids), links: new Set(links), order: ids };
}

/**
 * Draws the graph into `host`.
 *
 * @param trace   result of traceOf, or null
 * @param onPick  called with a node id when one is clicked
 * @param picked  a node id to mark as selected
 */
export function render(host, graph, trace, onPick, picked) {
  const size = layout(graph);
  const byId = new Map(graph.nodes.map(n => [n.id, n]));

  const svg = svgEl("svg", {
    id: "net", viewBox: `0 0 ${size.width} ${size.height}`,
    width: size.width, height: size.height,
    role: "img", "aria-label": "network topology",
  });
  if (trace) svg.classList.add("tracing");

  // Column captions, so a reader knows what a tier is without a legend lookup.
  for (const [i, tier] of size.columns.entries()) {
    svg.appendChild(svgText({
      class: "colhead", x: PAD_X + i * (NODE_W + COL_GAP), y: 14,
    }, TIER_LABEL[tier]));
  }

  const linkLayer = svgEl("g", {});
  const nodeLayer = svgEl("g", {});
  svg.append(linkLayer, nodeLayer);

  const busiest = Math.max(1, ...graph.links.map(l => l.weight || 0));
  for (const link of graph.links) {
    const a = byId.get(link.from), b = byId.get(link.to);
    if (!a || !b) continue;
    const id = link.from + "->" + link.to;
    const weight = link.weight || 0;
    const path = svgEl("path", {
      class: "link" + (weight > 0 ? " busy" : "") + (trace && trace.links.has(id) ? " lit" : ""),
      d: linkPath(a, b),
      // Thickness carries how many players are actually taking this path, which is
      // the difference between a configured edge and a used one.
      "stroke-width": 1.2 + 3.2 * (weight / busiest),
    });
    if (weight > 0) {
      const title = svgEl("title", {});
      title.textContent = `${a.title} → ${b.title}: ${weight} player${weight === 1 ? "" : "s"}`;
      path.appendChild(title);
    }
    linkLayer.appendChild(path);
  }

  for (const node of graph.nodes) {
    const g = svgEl("g", {
      class: "node " + node.tier + (node.health ? " h-" + node.health : "")
        + (node.warn ? " " + node.warn : "")
        + (trace && trace.nodes.has(node.id) ? " lit" : "")
        + (picked === node.id ? " sel" : "")
        + (node.tier === "INTERNET" ? "" : " pickable"),
      transform: `translate(${node.x},${node.y})`,
    });

    g.appendChild(svgEl("rect", { width: NODE_W, height: NODE_H, rx: 9 }));
    g.appendChild(svgEl("rect", { class: "flag", x: 1.5, y: 8, width: 3, height: NODE_H - 16, rx: 1.5 }));
    g.appendChild(svgText({ x: 14, y: 21, "font-weight": "600" }, node.title));
    if (node.sub) g.appendChild(svgText({ class: "sub", x: 14, y: 36 }, node.sub));

    if (node.badge !== null && node.badge !== undefined) {
      g.appendChild(svgText({
        x: NODE_W - 12, y: 21, "text-anchor": "end", "font-variant-numeric": "tabular-nums",
      }, node.badge));
    }
    if (node.load >= 0) {
      g.appendChild(svgEl("rect", { class: "load", x: 14, y: NODE_H - 11, width: NODE_W - 28, height: 4, rx: 2 }));
      g.appendChild(svgEl("rect", {
        class: "loadfill", x: 14, y: NODE_H - 11, rx: 2, height: 4,
        width: Math.max(0, Math.min(1, node.load)) * (NODE_W - 28),
      }));
    }

    if (node.tier !== "INTERNET" && onPick) {
      g.addEventListener("click", (e) => { e.stopPropagation(); onPick(node.id, node); });
    }
    nodeLayer.appendChild(g);
  }

  // A dot running the route, because a still picture of a path does not say which
  // way the traffic goes. Hidden entirely under prefers-reduced-motion.
  if (trace && trace.order.length > 1) {
    const d = trace.order
      .map(id => byId.get(id))
      .filter(Boolean)
      .map((n, i) => (i === 0 ? `M${n.x + NODE_W},${n.y + NODE_H / 2}` : `L${n.x},${n.y + NODE_H / 2}`))
      .join(" ");
    const spark = svgEl("circle", { id: "spark", r: 3.5 });
    const motion = svgEl("animateMotion", {
      dur: Math.max(1.6, trace.order.length * 0.7) + "s", repeatCount: "indefinite", path: d,
    });
    spark.appendChild(motion);
    svg.appendChild(spark);
  }

  host.replaceChildren(svg);
}

/* Sparklines for the §10 counters.
 *
 * Deliberately small: no axes, no gridlines, no legend. These sit four to a row and
 * answer one question each -- "is this rising, falling, or flat, and what is it now?"
 * A chart that needs axes to answer that is the wrong chart for the space.
 *
 * The proxy samples every five seconds and computes the rates itself, so this file
 * only ever draws what it is given. It does no differencing, which matters: totals
 * reset when the proxy restarts, and a client doing its own arithmetic would draw a
 * cliff down to a large negative number and call it data. */

const SVGNS = "http://www.w3.org/2000/svg";

const el = (name, attrs) => {
  const node = document.createElementNS(SVGNS, name);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v !== null && v !== undefined) node.setAttribute(k, String(v));
  }
  return node;
};

export const formatBytes = n => {
  if (!(n > 0)) return "0 B";
  const units = ["B", "kB", "MB", "GB"];
  let i = 0;
  while (n >= 1000 && i < units.length - 1) { n /= 1000; i++; }
  return (n < 10 && i > 0 ? n.toFixed(1) : Math.round(n)) + " " + units[i];
};

export const formatCount = n =>
  n >= 1_000_000 ? (n / 1_000_000).toFixed(1) + "M"
  : n >= 1_000 ? (n / 1_000).toFixed(1) + "k"
  : String(n);

/**
 * One filled sparkline.
 *
 * @param values  numbers, oldest first. Fewer than two draws nothing rather than a
 *                misleading flat line through a single reading.
 * @param ceiling optional shared maximum, so two charts meant to be compared to each
 *                other (bytes in against bytes out) share a scale
 */
export function sparkline(values, { width = 240, height = 46, tone = "", ceiling = null } = {}) {
  const svg = el("svg", {
    class: "spark " + tone, viewBox: `0 0 ${width} ${height}`,
    preserveAspectRatio: "none", "aria-hidden": "true",
  });
  if (!values || values.length < 2) return svg;

  // A flat non-zero series should sit near the top rather than being scaled to
  // nothing, and an all-zero series should sit flat on the floor. Both fall out of
  // giving the peak a floor of one.
  const peak = Math.max(1, ceiling ?? Math.max(...values));
  const step = width / (values.length - 1);
  const y = v => height - 1 - (Math.max(0, v) / peak) * (height - 2);

  const points = values.map((v, i) => `${(i * step).toFixed(2)},${y(v).toFixed(2)}`);
  svg.appendChild(el("path", {
    class: "fill",
    d: `M0,${height} L${points.join(" L")} L${width},${height} Z`,
  }));
  svg.appendChild(el("path", { class: "line", d: `M${points.join(" L")}` }));
  return svg;
}

/**
 * A titled card: label, current reading, and the shape it has been making.
 *
 * The reading is passed in already formatted rather than derived here, because
 * "1.2 MB/s" and "3 failed" are not the same kind of number and a formatter clever
 * enough to tell them apart is a formatter nobody can predict.
 */
export function chartCard({ label, reading, sub, values, tone, ceiling }) {
  const card = document.createElement("div");
  card.className = "chart";

  const head = document.createElement("div");
  head.className = "chart-head";
  const k = document.createElement("span");
  k.className = "k";
  k.textContent = label;
  const n = document.createElement("span");
  n.className = "n";
  n.textContent = reading;
  head.append(k, n);

  card.append(head, sparkline(values, { tone, ceiling }));

  if (sub) {
    const foot = document.createElement("div");
    foot.className = "chart-foot";
    foot.textContent = sub;
    card.appendChild(foot);
  }
  return card;
}

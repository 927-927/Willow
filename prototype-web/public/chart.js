// Line chart: share of the feed that is scam-health vs. hobby videos, over videos watched.
// Plain SVG, two series, legend + end labels, crosshair tooltip on hover.

const SERIES = [
  { key: 'hobby', name: 'Hobbies', color: 'var(--series-1)' },
  { key: 'scam', name: 'Scam health', color: 'var(--series-2)' },
];
const pct = (v) => `${Math.round(v * 100)}%`;
const NS = 'http://www.w3.org/2000/svg';

function svgEl(tag, attrs = {}) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  return el;
}

export function renderShareChart(container, history) {
  container.innerHTML = '';
  const legend = document.createElement('div');
  legend.className = 'legend';
  legend.innerHTML = SERIES.map((s) => `<span><i style="background:${s.color}"></i>${s.name}</span>`).join('');
  container.append(legend);

  const W = Math.max(280, container.clientWidth || 320);
  const H = 190;
  const m = { top: 10, right: 92, bottom: 28, left: 38 };
  const iw = W - m.left - m.right;
  const ih = H - m.top - m.bottom;
  const n = history.length;
  const xMax = Math.max(n - 1, 10);
  const x = (i) => m.left + (i / xMax) * iw;
  const y = (v) => m.top + (1 - v) * ih;

  const svg = svgEl('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H, role: 'img',
    'aria-label': `Feed share over ${n - 1} videos. Scam health ${pct(history.at(-1).scam)}, hobbies ${pct(history.at(-1).hobby)}.` });

  for (const t of [0, 0.25, 0.5, 0.75, 1]) {
    svg.append(svgEl('line', { x1: m.left, x2: m.left + iw, y1: y(t), y2: y(t), class: t === 0 ? 'axis' : 'grid' }));
    const label = svgEl('text', { x: m.left - 6, y: y(t) + 4, 'text-anchor': 'end', class: 'tick' });
    label.textContent = pct(t);
    svg.append(label);
  }
  const xTicks = [0, Math.round(xMax / 2), xMax];
  for (const t of xTicks) {
    const label = svgEl('text', { x: x(t), y: H - 10, 'text-anchor': t === 0 ? 'start' : t === xMax ? 'end' : 'middle', class: 'tick' });
    label.textContent = t === xMax ? `${t} videos` : t;
    svg.append(label);
  }

  const ends = [];
  for (const s of SERIES) {
    const d = history.map((h, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(h[s.key]).toFixed(1)}`).join('');
    svg.append(svgEl('path', { d, fill: 'none', stroke: s.color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
    const last = history.at(-1)[s.key];
    svg.append(svgEl('circle', { cx: x(n - 1), cy: y(last), r: 4, fill: s.color, class: 'ring' }));
    ends.push({ s, yPos: y(last), value: last });
  }
  // End labels only when they don't collide; otherwise the legend + tooltip carry identity.
  if (Math.abs(ends[0].yPos - ends[1].yPos) >= 16) {
    for (const e of ends) {
      const t = svgEl('text', { x: x(n - 1) + 9, y: e.yPos + 4, class: 'end-label' });
      t.textContent = `${e.s.name} ${pct(e.value)}`;
      svg.append(t);
    }
  }

  // Hover layer
  const cross = svgEl('line', { y1: m.top, y2: m.top + ih, class: 'crosshair', visibility: 'hidden' });
  const dots = SERIES.map((s) => svgEl('circle', { r: 4, fill: s.color, class: 'ring', visibility: 'hidden' }));
  const hit = svgEl('rect', { x: m.left, y: m.top, width: iw, height: ih, fill: 'transparent' });
  svg.append(cross, ...dots, hit);

  const tip = document.createElement('div');
  tip.className = 'chart-tip';
  tip.hidden = true;

  const showAt = (clientX) => {
    const rect = svg.getBoundingClientRect();
    const px = ((clientX - rect.left) / rect.width) * W;
    const i = Math.max(0, Math.min(n - 1, Math.round(((px - m.left) / iw) * xMax)));
    const h = history[i];
    cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i));
    cross.setAttribute('visibility', 'visible');
    SERIES.forEach((s, k) => {
      dots[k].setAttribute('cx', x(i)); dots[k].setAttribute('cy', y(h[s.key]));
      dots[k].setAttribute('visibility', 'visible');
    });
    tip.innerHTML = `<b>After ${i} videos</b>${SERIES.map((s) => `<span><i style="background:${s.color}"></i>${s.name} <b>${pct(h[s.key])}</b></span>`).join('')}`;
    tip.hidden = false;
    const left = (x(i) / W) * rect.width;
    tip.style.left = `${Math.min(rect.width - 150, Math.max(0, left + 10))}px`;
  };
  const hide = () => {
    cross.setAttribute('visibility', 'hidden');
    dots.forEach((d) => d.setAttribute('visibility', 'hidden'));
    tip.hidden = true;
  };
  hit.addEventListener('pointermove', (e) => showAt(e.clientX));
  hit.addEventListener('pointerdown', (e) => showAt(e.clientX));
  hit.addEventListener('pointerleave', hide);

  const wrap = document.createElement('div');
  wrap.className = 'chart-wrap';
  wrap.append(svg, tip);
  container.append(wrap);
}

export function renderShareTable(container, history) {
  const step = Math.max(1, Math.floor(history.length / 15));
  const rows = history.map((h, i) => ({ i, h })).filter(({ i }) => i % step === 0 || i === history.length - 1);
  container.innerHTML = `<table>
    <thead><tr><th>Videos watched</th><th>Hobbies</th><th>Scam health</th></tr></thead>
    <tbody>${rows.map(({ i, h }) => `<tr><td>${i}</td><td>${pct(h.hobby)}</td><td>${pct(h.scam)}</td></tr>`).join('')}</tbody>
  </table>`;
}

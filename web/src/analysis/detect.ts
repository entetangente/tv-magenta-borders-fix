import { downscale, luma, median, sample1, type Point, type RGBAImage } from './image';

/** Gerade in Normalform: n·p = d mit |n| = 1. */
interface Line {
  nx: number;
  ny: number;
  d: number;
}

export interface EdgeFit {
  line: Line;
  /** Anteil der Kantenpunkte, die zur Geraden passen. */
  inlierFraction: number;
  /** Größte Abweichung einer an die Punkte angepassten Parabel von der Geraden, relativ zur Kantenlänge. */
  bow: number;
  /** Median der Kantenbreite (10–90 %) in Pixeln. */
  edgeWidth: number;
}

export interface Detection {
  /** oben links, oben rechts, unten rechts, unten links (Bildkoordinaten) */
  corners: Point[];
  edges: EdgeFit[];
  /** Die helle Fläche berührt den Bildrand. */
  touchesBorder: boolean;
  /** Fläche des Bildschirms relativ zur Fotofläche. */
  areaFraction: number;
}

/**
 * Fläche des Bildschirms: Ausgehend von der Bildmitte werden alle verbundenen
 * Pixel gesammelt, deren Helligkeit zur Mitte passt (Testbild = gleichmäßiges
 * Grau). Eine helle Wand hinter dem Fernseher ist durch den dunklen Rahmen
 * getrennt und wird so nicht mitgenommen. Vorher wird die Maske „geöffnet“
 * (erodiert), damit dünne Brücken über Spiegelungen oder einen hellen
 * Rahmen die Fläche nicht mit der Wand verbinden.
 */
function screenRegion(Y: Float32Array, w: number, h: number): { pixels: Int32Array; touchesBorder: boolean } | null {
  // Helligkeit des Bildschirms: Median eines kleinen Felds in der Bildmitte.
  // Ist es dort dunkel (mitfotografierter Hinweistext), das mittlere Drittel.
  const boxMedian = (fx: number, fy: number) => {
    const vals: number[] = [];
    for (let y = Math.floor(h * (0.5 - fy)); y < Math.ceil(h * (0.5 + fy)); y++) {
      for (let x = Math.floor(w * (0.5 - fx)); x < Math.ceil(w * (0.5 + fx)); x++) vals.push(Y[y * w + x]);
    }
    return median(vals);
  };
  let c = boxMedian(0.03, 0.03);
  if (!(c > 15)) c = boxMedian(1 / 6, 1 / 6);
  if (!(c > 15)) return null;
  // Erlaubter Bereich: Randabfall des Panels und Vignettierung der Kamera
  const lo = c * 0.5;
  // Nach oben eng: Die Mitte ist meist die hellste Stelle des Bildschirms,
  // eine Wand gleicher Helligkeit soll nicht dazugehören.
  const hi = Math.min(255.5, c * 1.12 + 6);
  let mask: Uint8Array = new Uint8Array(w * h);
  for (let i = 0; i < mask.length; i++) mask[i] = Y[i] >= lo && Y[i] <= hi ? 1 : 0;
  const r = Math.max(1, Math.round(Math.min(w, h) / 250));
  mask = erode(mask, w, h, r);
  // Startpunkt: passender Pixel möglichst nah an der Bildmitte
  let seed = -1;
  let best = Infinity;
  for (let y = Math.floor(h / 3); y < Math.ceil((2 * h) / 3); y++) {
    for (let x = Math.floor(w / 3); x < Math.ceil((2 * w) / 3); x++) {
      if (!mask[y * w + x]) continue;
      const d = (x - w / 2) ** 2 + (y - h / 2) ** 2;
      if (d < best) { best = d; seed = y * w + x; }
    }
  }
  if (seed < 0) return null;
  const comp = floodFill(mask, w, h, seed);
  // wieder auf ursprüngliche Größe bringen
  const grown = dilate(comp.mask, w, h, r);
  const pixels: number[] = [];
  let touches = false;
  for (let p = 0; p < grown.length; p++) {
    if (!grown[p]) continue;
    pixels.push(p);
    const x = p % w;
    const y = (p - x) / w;
    if (x === 0 || y === 0 || x === w - 1 || y === h - 1) touches = true;
  }
  return { pixels: Int32Array.from(pixels), touchesBorder: touches };
}

function floodFill(mask: Uint8Array, w: number, h: number, seed: number): { mask: Uint8Array } {
  const out = new Uint8Array(w * h);
  const stack = new Int32Array(w * h);
  let sp = 0;
  stack[sp++] = seed;
  out[seed] = 1;
  while (sp > 0) {
    const p = stack[--sp];
    const x = p % w;
    const y = (p - x) / w;
    if (x > 0 && mask[p - 1] && !out[p - 1]) { out[p - 1] = 1; stack[sp++] = p - 1; }
    if (x < w - 1 && mask[p + 1] && !out[p + 1]) { out[p + 1] = 1; stack[sp++] = p + 1; }
    if (y > 0 && mask[p - w] && !out[p - w]) { out[p - w] = 1; stack[sp++] = p - w; }
    if (y < h - 1 && mask[p + w] && !out[p + w]) { out[p + w] = 1; stack[sp++] = p + w; }
  }
  return { mask: out };
}

/** Binäre Erosion/Dilatation mit quadratischem Fenster (separierbar). */
function morph(src: Uint8Array, w: number, h: number, r: number, erodeMode: boolean): Uint8Array {
  const tmp = new Uint8Array(w * h);
  const out = new Uint8Array(w * h);
  const want = erodeMode ? 0 : 1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let v = erodeMode ? 1 : 0;
      for (let k = -r; k <= r && v !== want; k++) {
        const xx = x + k;
        const s = xx < 0 || xx >= w ? 0 : src[y * w + xx];
        if (s === want) v = want;
      }
      tmp[y * w + x] = v;
    }
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let v = erodeMode ? 1 : 0;
      for (let k = -r; k <= r && v !== want; k++) {
        const yy = y + k;
        const s = yy < 0 || yy >= h ? 0 : tmp[yy * w + x];
        if (s === want) v = want;
      }
      out[y * w + x] = v;
    }
  }
  return out;
}
const erode = (m: Uint8Array, w: number, h: number, r: number) => morph(m, w, h, r, true);
const dilate = (m: Uint8Array, w: number, h: number, r: number) => morph(m, w, h, r, false);

function fitLineTLS(pts: Point[]): Line {
  let mx = 0;
  let my = 0;
  for (const p of pts) { mx += p.x; my += p.y; }
  mx /= pts.length;
  my /= pts.length;
  let sxx = 0;
  let sxy = 0;
  let syy = 0;
  for (const p of pts) {
    const dx = p.x - mx;
    const dy = p.y - my;
    sxx += dx * dx; sxy += dx * dy; syy += dy * dy;
  }
  // Richtung = Eigenvektor zum größten Eigenwert; Normale steht senkrecht darauf.
  const theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
  const nx = -Math.sin(theta);
  const ny = Math.cos(theta);
  return { nx, ny, d: nx * mx + ny * my };
}

function intersect(a: Line, b: Line): Point {
  const det = a.nx * b.ny - a.ny * b.nx;
  return { x: (a.d * b.ny - a.ny * b.d) / det, y: (a.nx * b.d - a.d * b.nx) / det };
}

/** Deterministischer Zufall, damit Ergebnisse reproduzierbar sind. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

interface EdgePoint extends Point {
  t: number;
  width: number;
}

/**
 * Sucht entlang der Normalen den steilsten Helligkeitsabfall (innen hell, außen dunkel).
 */
function findEdgePoints(Y: Float32Array, w: number, h: number, p: Point, q: Point, out: Point, range: number): EdgePoint[] {
  const pts: EdgePoint[] = [];
  const N = 48;
  const step = 0.5;
  const n = Math.ceil(range / step);
  const prof = new Float32Array(2 * n + 1);
  for (let i = 0; i < N; i++) {
    const t = 0.08 + (0.84 * i) / (N - 1);
    const sx = p.x + (q.x - p.x) * t;
    const sy = p.y + (q.y - p.y) * t;
    for (let k = -n; k <= n; k++) prof[k + n] = sample1(Y, w, h, sx + out.x * k * step, sy + out.y * k * step);
    // steilster Abfall
    let best = 0;
    let bi = -1;
    for (let k = 1; k < prof.length - 1; k++) {
      const g = prof[k - 1] - prof[k + 1];
      if (g > best) { best = g; bi = k; }
    }
    if (bi < 2 || bi > prof.length - 3) continue;
    const gm = prof[bi - 2] - prof[bi];
    const g0 = prof[bi - 1] - prof[bi + 1];
    const gp = prof[bi] - prof[bi + 2];
    const den = gm - 2 * g0 + gp;
    const off = den !== 0 ? (0.5 * (gm - gp)) / den : 0;
    const pos = (bi - n + Math.max(-0.5, Math.min(0.5, off))) * step;
    // Kantenbreite 10–90 %
    const inner = median(prof.slice(0, Math.max(3, bi - Math.ceil(6 / step))));
    // Außen: dunkelster Wert hinter der Kante (Rahmen). Ein Median weiter außen
    // würde eine helle Wand mitzählen.
    let outer = Infinity;
    for (let k = bi; k < prof.length; k++) outer = Math.min(outer, prof[k]);
    if (!(inner - outer > 8)) continue;
    const hi = outer + 0.9 * (inner - outer);
    const lo = outer + 0.1 * (inner - outer);
    let k90 = bi;
    while (k90 > 0 && prof[k90] < hi) k90--;
    let k10 = bi;
    while (k10 < prof.length - 1 && prof[k10] > lo) k10++;
    pts.push({ x: sx + out.x * pos, y: sy + out.y * pos, t, width: (k10 - k90) * step });
  }
  return pts;
}

function fitEdge(points: EdgePoint[], tol: number, length: number): EdgeFit | null {
  if (points.length < 8) return null;
  const rand = rng(12345);
  let bestInliers: EdgePoint[] = [];
  for (let it = 0; it < 120; it++) {
    const a = points[Math.floor(rand() * points.length)];
    const b = points[Math.floor(rand() * points.length)];
    if (a === b) continue;
    const L = fitLineTLS([a, b]);
    const inl = points.filter((p) => Math.abs(L.nx * p.x + L.ny * p.y - L.d) < tol);
    if (inl.length > bestInliers.length) bestInliers = inl;
  }
  if (bestInliers.length < 6) return null;
  const line = fitLineTLS(bestInliers);
  // Durchbiegung: Parabel d(t) = c0 + c1 s + c2 s² mit s = t − 0.5
  const A: number[][] = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  const bv = [0, 0, 0];
  for (const p of bestInliers) {
    const s = p.t - 0.5;
    const d = line.nx * p.x + line.ny * p.y - line.d;
    const v = [1, s, s * s];
    for (let i = 0; i < 3; i++) {
      bv[i] += v[i] * d;
      for (let j = 0; j < 3; j++) A[i][j] += v[i] * v[j];
    }
  }
  let bow = 0;
  try {
    const det =
      A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) -
      A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0]) +
      A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
    if (Math.abs(det) > 1e-12) {
      // Cramer für c2
      const det2 =
        A[0][0] * (A[1][1] * bv[2] - bv[1] * A[2][1]) -
        A[0][1] * (A[1][0] * bv[2] - bv[1] * A[2][0]) +
        bv[0] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
      const c2 = det2 / det;
      bow = Math.abs(c2 * 0.42 * 0.42) / length;
    }
  } catch {
    bow = 0;
  }
  return {
    line,
    inlierFraction: bestInliers.length / points.length,
    bow,
    edgeWidth: median(bestInliers.map((p) => p.width)),
  };
}

function polygonArea(c: Point[]): number {
  let s = 0;
  for (let i = 0; i < c.length; i++) {
    const a = c[i];
    const b = c[(i + 1) % c.length];
    s += a.x * b.y - b.x * a.y;
  }
  return Math.abs(s) / 2;
}

/**
 * Findet den leuchtenden Bildschirm im Foto. Liefert null, wenn keine
 * plausible helle Fläche gefunden wird.
 */
export function detectScreen(img: RGBAImage): { detection: Detection | null; touchesBorder: boolean } {
  let touches = false;
  const detection = detect(img, (t) => (touches = t));
  return { detection, touchesBorder: touches };
}

function detect(img: RGBAImage, reportBorder: (t: boolean) => void): Detection | null {
  const { width: w, height: h } = img;
  const Y = luma(img);
  const f = Math.max(1, Math.floor(Math.max(w, h) / 700));
  const small = downscale(Y, w, h, f);
  const comp = screenRegion(small.data, small.w, small.h);
  if (!comp || comp.pixels.length < 0.02 * small.data.length) return null;
  reportBorder(comp.touchesBorder);

  // Grobe Ecken aus den Extrempunkten
  let tl = 0, tr = 0, br = 0, bl = 0;
  let vtl = Infinity, vtr = -Infinity, vbr = -Infinity, vbl = Infinity;
  for (const p of comp.pixels) {
    const x = p % small.w;
    const y = (p - x) / small.w;
    const s = x + y;
    const d = x - y;
    if (s < vtl) { vtl = s; tl = p; }
    if (s > vbr) { vbr = s; br = p; }
    if (d > vtr) { vtr = d; tr = p; }
    if (d < vbl) { vbl = d; bl = p; }
  }
  const toImg = (p: number): Point => {
    const x = p % small.w;
    const y = (p - x) / small.w;
    return { x: (x + 0.5) * f, y: (y + 0.5) * f };
  };
  const rough = [toImg(tl), toImg(tr), toImg(br), toImg(bl)];
  const cx = rough.reduce((s, p) => s + p.x, 0) / 4;
  const cy = rough.reduce((s, p) => s + p.y, 0) / 4;

  const range = Math.max(10, 4 * f + 6);
  const tol = Math.max(1.5, 0.0012 * Math.max(w, h));
  const edges: EdgeFit[] = [];
  for (let i = 0; i < 4; i++) {
    const p = rough[i];
    const q = rough[(i + 1) % 4];
    const len = Math.hypot(q.x - p.x, q.y - p.y);
    let nx = -(q.y - p.y) / len;
    let ny = (q.x - p.x) / len;
    const mx = (p.x + q.x) / 2;
    const my = (p.y + q.y) / 2;
    if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny; }
    const pts = findEdgePoints(Y, w, h, p, q, { x: nx, y: ny }, range);
    const fit = fitEdge(pts, tol, len);
    if (!fit) return null;
    edges.push(fit);
  }
  // Ecke i liegt zwischen Kante i−1 und Kante i
  const corners = [0, 1, 2, 3].map((i) => intersect(edges[(i + 3) % 4].line, edges[i].line));
  if (corners.some((c) => !isFinite(c.x) || !isFinite(c.y))) return null;
  return {
    corners,
    edges,
    touchesBorder: comp.touchesBorder,
    areaFraction: polygonArea(corners) / (w * h),
  };
}

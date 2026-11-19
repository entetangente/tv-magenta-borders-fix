import { homography, applyH, type Mat3 } from '../src/analysis/homography';
import { lin, linearToSrgb } from '../src/analysis/color';
import type { Point, RGBAImage } from '../src/analysis/image';
import type { CorrectionMap } from '../src/analysis/solve';

export interface SynthOptions {
  w: number;
  h: number;
  corners: Point[];
  level: number;
  /** Stärke des lila Rands (0 = fehlerfrei) */
  defect: number;
  map?: CorrectionMap;
  strength?: number;
  noise: number;
  exposure: number;
  barrel: number;
  /** Helligkeit der Wand hinter dem Fernseher (linear, 0 = dunkler Raum) */
  wall: number;
  /** Breite des dunklen Rahmens um das Bild, Anteil der Bildschirmbreite */
  bezel: number;
  /** dunkler Hinweis-Kasten in der Bildschirmmitte mitfotografiert */
  hintBox: boolean;
  blur: number;
  seed: number;
}

export const DEFAULT_CORNERS: Point[] = [
  { x: 210, y: 230 },
  { x: 1400, y: 210 },
  { x: 1420, y: 900 },
  { x: 190, y: 920 },
];

export function defaults(o: Partial<SynthOptions> = {}): SynthOptions {
  return {
    w: 1600, h: 1200, corners: DEFAULT_CORNERS, level: 0.75, defect: 1, noise: 1.2,
    exposure: 1.1, barrel: 0, blur: 0, seed: 1, wall: 0, bezel: 0.012, hintBox: false, ...o,
  };
}

/** Panel-Fehler: Grün (und etwas Rot) fehlt zu den Rändern hin, Ecken stärker. */
export function panelGain(u: number, v: number, defect: number): [number, number, number] {
  const ar = 16 / 9;
  const dl = u * ar, dr = (1 - u) * ar, dt = v, db = 1 - v;
  // linke Seite etwas stärker und breiter (ungleichmäßig, wie in echt)
  const e =
    Math.exp(-dl / 0.07) * 1.2 + Math.exp(-dr / 0.045) + Math.exp(-dt / 0.05) + Math.exp(-db / 0.04) * 0.8;
  const k = Math.min(1.6, e) * defect;
  const fall = 1 - 0.08 * Math.min(1, e); // leichter Helligkeitsabfall
  return [fall * (1 - 0.03 * k), fall * (1 - 0.14 * k), fall * (1 - 0.01 * k)];
}

function sampleMap(map: CorrectionMap, u: number, v: number, strength: number, out: number[]): number {
  const { gw, gh } = map;
  const fx = Math.min(Math.max(u * gw - 0.5, 0), gw - 1.001);
  const fy = Math.min(Math.max(v * gh - 0.5, 0), gh - 1.001);
  const x0 = Math.floor(fx), y0 = Math.floor(fy);
  const tx = fx - x0, ty = fy - y0;
  const idx = [y0 * gw + x0, y0 * gw + x0 + 1, (y0 + 1) * gw + x0, (y0 + 1) * gw + x0 + 1];
  const wts = [(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty];
  let a = 0;
  out[0] = out[1] = out[2] = 0;
  for (let k = 0; k < 4; k++) {
    a += wts[k] * map.a[idx[k]];
    for (let c = 0; c < 3; c++) out[c] += wts[k] * map.P[idx[k] * 3 + c];
  }
  const s = strength;
  const as = Math.min(1, a * s);
  for (let c = 0; c < 3; c++) out[c] *= s;
  return as;
}

export function synthPhoto(opts: Partial<SynthOptions> = {}): RGBAImage {
  const o = defaults(opts);
  const { w, h } = o;
  const H: Mat3 = homography(o.corners, [
    { x: 0, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 },
  ]);
  let s = o.seed >>> 0;
  const rnd = () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
  const gauss = () => {
    const u = Math.max(1e-9, rnd());
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rnd());
  };
  const data = new Uint8ClampedArray(w * h * 4);
  const P = [0, 0, 0];
  const cx = w / 2, cy = h / 2, rn = Math.hypot(cx, cy);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let px = x + 0.5, py = y + 0.5;
      if (o.barrel) {
        const r2 = ((px - cx) ** 2 + (py - cy) ** 2) / (rn * rn);
        px = cx + (px - cx) * (1 + o.barrel * r2);
        py = cy + (py - cy) * (1 + o.barrel * r2);
      }
      const uv = applyH(H, px, py);
      const i = (y * w + x) * 4;
      let rgb: number[];
      if (uv.x >= 0 && uv.x <= 1 && uv.y >= 0 && uv.y <= 1) {
        const g = panelGain(uv.x, uv.y, o.defect);
        let a = 0;
        if (o.map) a = sampleMap(o.map, uv.x, uv.y, o.strength ?? 1, P);
        const vig = 1 - 0.18 * (((px - cx) ** 2 + (py - cy) ** 2) / (rn * rn));
        const inHint = o.hintBox && Math.abs(uv.x - 0.5) < 0.09 && Math.abs(uv.y - 0.5) < 0.04;
        rgb = inHint ? [0.01, 0.01, 0.01] : [0, 1, 2].map((c) => {
          const xin = (1 - a) * o.level + P[c]; // P = a·O vormultipliziert
          return g[c] * lin(xin) * o.exposure * vig;
        });
      } else {
        const b = o.bezel;
        const inFrame = uv.x > -b && uv.x < 1 + b && uv.y > -b * 16 / 9 && uv.y < 1 + b * 16 / 9;
        // Wand: von oben nach unten etwas heller, leicht wärmer
        const wl = o.wall * (0.85 + 0.3 * (py / h));
        rgb = inFrame || o.wall === 0 ? [0.003, 0.003, 0.003] : [wl * 1.02, wl, wl * 0.93];
      }
      for (let c = 0; c < 3; c++) data[i + c] = linearToSrgb(rgb[c]) * 255 + gauss() * o.noise;
      data[i + 3] = 255;
    }
  }
  const img = { width: w, height: h, data };
  return o.blur ? boxBlur(img, o.blur) : img;
}

function boxBlur(img: RGBAImage, r: number): RGBAImage {
  const { width: w, height: h, data } = img;
  const tmp = new Float32Array(w * h * 4);
  const out = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) for (let c = 0; c < 4; c++) {
    let s = 0, n = 0;
    for (let k = -r; k <= r; k++) { const xx = x + k; if (xx >= 0 && xx < w) { s += data[(y * w + xx) * 4 + c]; n++; } }
    tmp[(y * w + x) * 4 + c] = s / n;
  }
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) for (let c = 0; c < 4; c++) {
    let s = 0, n = 0;
    for (let k = -r; k <= r; k++) { const yy = y + k; if (yy >= 0 && yy < h) { s += tmp[(yy * w + x) * 4 + c]; n++; } }
    out[(y * w + x) * 4 + c] = s / n;
  }
  return { width: w, height: h, data: out };
}

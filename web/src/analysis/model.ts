import { chromaDistance, chromaVector, srgbToLinear, type Vec3 } from './color';
import { gaussBlur, GW, GH, isCenterCell, smoothstep } from './grid';

/** Ein ausgewertetes Foto: angezeigter Grauwert (kodiert) und Rasterwerte (kodiert, Kamera). */
export interface Capture {
  level: number;
  cells: Float32Array;
}

/**
 * Panel-Modell je Zelle und Helligkeitsstufe: Ein Eingangswert x erscheint
 * (im linearen Licht, relativ zur Bildmitte) als g ⊙ lin(x).
 */
export interface PanelModel {
  gw: number;
  gh: number;
  levels: number[];
  /** je Stufe: g (GW×GH×3), lineares Verhältnis Zelle/Mitte je Kanal */
  g: Float32Array[];
  /** je Stufe: Farbe der Mitte im linearen Kameraraum */
  ref: Vec3[];
  /** geglätteter Farbabstand (ΔE2000, nur Farbe) je Zelle */
  D: Float32Array;
  /** Rauschgrenze und obere Schwelle der Betroffenheitsmaske */
  t0: number;
  t1: number;
  /** Betroffenheitsmaske M (0–1), exakt 0 im unbetroffenen Bereich */
  mask: Float32Array;
}

export function cellsToLinear(cells: Float32Array): Float32Array {
  const out = new Float32Array(cells.length);
  for (let i = 0; i < cells.length; i++) out[i] = srgbToLinear(cells[i]);
  return out;
}

export function centerRef(linCells: Float32Array, gw = GW, gh = GH): Vec3 {
  const acc: number[][] = [[], [], []];
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) {
    if (!isCenterCell(x, y, gw, gh)) continue;
    const o = (y * gw + x) * 3;
    for (let c = 0; c < 3; c++) acc[c].push(linCells[o + c]);
  }
  return acc.map((a) => {
    a.sort((p, q) => p - q);
    return a[a.length >> 1];
  }) as Vec3;
}

/** Farbabstand jeder Zelle zur Mitte (nur Farbe, Helligkeit ausgeglichen). */
export function chromaErrorMap(linCells: Float32Array, ref: Vec3, gw = GW, gh = GH): Float32Array {
  const D = new Float32Array(gw * gh);
  for (let i = 0; i < D.length; i++) {
    D[i] = chromaDistance([linCells[i * 3], linCells[i * 3 + 1], linCells[i * 3 + 2]], ref);
  }
  return D;
}

function rms(values: number[]): number {
  if (values.length === 0) return 0;
  let s = 0;
  for (const v of values) s += v * v;
  return Math.sqrt(s / values.length);
}

export function buildModel(captures: Capture[], gw = GW, gh = GH): PanelModel {
  const sorted = [...captures].sort((a, b) => b.level - a.level);
  const levels = sorted.map((c) => c.level);
  const g: Float32Array[] = [];
  const ref: Vec3[] = [];
  const Dsum = new Float32Array(gw * gh);
  let Dn = 0;
  for (const cap of sorted) {
    const lin = cellsToLinear(cap.cells);
    const r = centerRef(lin, gw, gh);
    ref.push(r);
    const gl = new Float32Array(lin.length);
    for (let i = 0; i < gw * gh; i++) for (let c = 0; c < 3; c++) gl[i * 3 + c] = lin[i * 3 + c] / Math.max(1e-6, r[c]);
    g.push(gaussBlur(gl, gw, gh, 0.7, 3));
    // Für die Maske zählen die hellen Stufen (dort stört der Fehler).
    if (cap.level >= 0.45 || sorted.length === 1) {
      const D = chromaErrorMap(lin, r, gw, gh);
      for (let i = 0; i < D.length; i++) Dsum[i] += D[i];
      Dn++;
    }
  }
  for (let i = 0; i < Dsum.length; i++) Dsum[i] /= Math.max(1, Dn);
  const D = gaussBlur(Dsum, gw, gh, 1.0);
  const center: number[] = [];
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) if (isCenterCell(x, y, gw, gh)) center.push(D[y * gw + x]);
  // Schwelle aus der Messung: dreifache Streuung der Mitte, mindestens 0.6 ΔE.
  const t0 = Math.max(0.6, 3 * rms(center));
  const t1 = 2 * t0;
  const mask = new Float32Array(gw * gh);
  for (let i = 0; i < mask.length; i++) mask[i] = smoothstep(t0, t1, D[i]);
  return { gw, gh, levels, g, ref, D, t0, t1, mask };
}

/** Index der Stufe im Modell, die level am nächsten ist. */
export function nearestLevel(model: PanelModel, level: number): number {
  let best = 0;
  for (let i = 1; i < model.levels.length; i++) {
    if (Math.abs(model.levels[i] - level) < Math.abs(model.levels[best] - level)) best = i;
  }
  return best;
}

export { chromaVector };

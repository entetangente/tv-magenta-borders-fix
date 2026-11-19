import { chromaDistance, chromaVector, dlin, lin, type Vec3 } from './color';
import { gaussBlur, limitSlope } from './grid';
import type { PanelModel } from './model';

export interface SolveOptions {
  /** Korrekturgrad: Anteil, um den die Abweichung verringert werden soll (0.5–0.9). */
  beta: number;
  /** Schwarz-Schutz: Strafe für das Aufhellen von Schwarz. */
  lambda: number;
  /** Glattheit zwischen Nachbarzellen. */
  mu: number;
  /** Maximale Änderung der Deckkraft zwischen Nachbarzellen. */
  maxStep: number;
  iterations: number;
}

/** Rec.-709-Gewichte für die Helligkeit Y. */
const YW = [0.2126, 0.7152, 0.0722];
/** Gewicht des Helligkeitsfehlers relativ zum Farbfehler. */
const LUMA_WEIGHT = 0.1;

export const DEFAULT_SOLVE: SolveOptions = { beta: 0.75, lambda: 1, mu: 0.02, maxStep: 0.01, iterations: 500 };

/** Korrekturkarte: Deckkraft a und vormultiplizierte Farbe P = a·O je Zelle. */
export interface CorrectionMap {
  gw: number;
  gh: number;
  a: Float32Array;
  P: Float32Array;
}

/** Vorhergesagte relative Helligkeit je Kanal bei Eingang L mit Overlay (a, O). */
export function predict(g: ArrayLike<number>, o: number, L: number, a: number, O: ArrayLike<number>, out: number[]): void {
  const lL = lin(L);
  for (let c = 0; c < 3; c++) {
    const x = (1 - a) * L + a * O[c];
    out[c] = (g[o + c] * lin(x)) / lL;
  }
}

/** Zielwert: Abweichung vom schwächsten Kanal um beta verringern. */
function target(g: ArrayLike<number>, o: number, beta: number, out: number[]): void {
  const m = Math.min(g[o], g[o + 1], g[o + 2]);
  for (let c = 0; c < 3; c++) out[c] = g[o + c] - beta * (g[o + c] - m);
}

/**
 * Berechnet die Korrekturkarte für alle Zellen gemeinsam (projiziertes
 * Gradientenverfahren mit Adam) und setzt danach die Leitprinzipien um:
 * Glätten, Maske, Steigungsgrenze, keine Überkorrektur, exakte Nullen.
 */
export function solveMap(model: PanelModel, opt: SolveOptions = DEFAULT_SOLVE): CorrectionMap {
  const { gw, gh, mask } = model;
  const n = gw * gh;
  // Aktive Zellen: Maske > 0, um 3 Zellen erweitert (für die Glattheit).
  const active = new Uint8Array(n);
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) {
    let on = false;
    for (let dy = -3; dy <= 3 && !on; dy++) for (let dx = -3; dx <= 3 && !on; dx++) {
      const xx = x + dx;
      const yy = y + dy;
      if (xx >= 0 && yy >= 0 && xx < gw && yy < gh && mask[yy * gw + xx] > 0) on = true;
    }
    active[y * gw + x] = on ? 1 : 0;
  }
  // Variablen: Deckkraft a und vormultiplizierte Farbe P = a·O mit 0 ≤ P ≤ a.
  // (Mit O statt P wäre der Gradient nach O bei a = 0 null, und die Lösung
  // bliebe im Startpunkt hängen.)
  const a = new Float32Array(n);
  const Pv = new Float32Array(n * 3);
  const ga = new Float32Array(n);
  const gP = new Float32Array(n * 3);
  const ma = new Float32Array(n), va = new Float32Array(n);
  const mP = new Float32Array(n * 3), vP = new Float32Array(n * 3);
  // Helle Stufen zählen deutlich mehr: Dort ist derselbe Farbstich viel
  // sichtbarer (ΔE wächst mit der Helligkeit).
  const w = model.levels.map((L) => L * L);
  const wsum = w.reduce((s, v) => s + v, 0);
  const T = [0, 0, 0];
  const xs = [0, 0, 0], Pc = [0, 0, 0], dPc = [0, 0, 0], dLdP = [0, 0, 0];
  const b1 = 0.9, b2 = 0.999, eps = 1e-8;
  const lr = 0.003;

  for (let it = 1; it <= opt.iterations; it++) {
    ga.fill(0);
    gP.fill(0);
    for (let i = 0; i < n; i++) {
      if (!active[i]) continue;
      const ai = a[i];
      for (let l = 0; l < model.levels.length; l++) {
        const L = model.levels[l];
        const g = model.g[l];
        const lL = lin(L);
        target(g, i * 3, opt.beta, T);
        const wl = w[l] / wsum;
        // Fehler getrennt nach Farbe (Verhältnisse zur Helligkeit Y) und
        // Helligkeit. Die Farbe zählt voll, die Helligkeit nur mit LUMA_WEIGHT:
        // Ein etwas dunklerer Rand fällt weniger auf als ein farbiger.
        for (let c = 0; c < 3; c++) {
          xs[c] = (1 - ai) * L + Pv[i * 3 + c];
          Pc[c] = (g[i * 3 + c] * lin(xs[c])) / lL;
          dPc[c] = (g[i * 3 + c] * dlin(xs[c])) / lL;
        }
        const YP = YW[0] * Pc[0] + YW[1] * Pc[1] + YW[2] * Pc[2];
        const YT = YW[0] * T[0] + YW[1] * T[1] + YW[2] * T[2];
        // dLoss/dP_k
        const rY = (YP - YT) / YT;
        for (let k = 0; k < 3; k++) dLdP[k] = (2 * LUMA_WEIGHT * rY * YW[k]) / YT;
        for (let c = 0; c < 3; c++) {
          const rc = Pc[c] / YP - T[c] / YT;
          for (let k = 0; k < 3; k++) {
            const d = ((c === k ? YP : 0) - Pc[c] * YW[k]) / (YP * YP);
            dLdP[k] += 2 * rc * d;
          }
        }
        for (let k = 0; k < 3; k++) {
          const gk = wl * dLdP[k] * dPc[k];
          ga[i] += gk * -L;
          gP[i * 3 + k] += gk;
        }
      }
      // Schwarz-Schutz: Schwarz wird um P aufgehellt.
      for (let c = 0; c < 3; c++) gP[i * 3 + c] += 2 * opt.lambda * 0.1 * Pv[i * 3 + c];
      // Glattheit (4er-Nachbarschaft)
      const x = i % gw;
      const y = (i - x) / gw;
      const nb = [x > 0 ? i - 1 : -1, x < gw - 1 ? i + 1 : -1, y > 0 ? i - gw : -1, y < gh - 1 ? i + gw : -1];
      for (const j of nb) {
        if (j < 0) continue;
        ga[i] += 2 * opt.mu * (ai - a[j]);
        for (let c = 0; c < 3; c++) gP[i * 3 + c] += 2 * opt.mu * (Pv[i * 3 + c] - Pv[j * 3 + c]);
      }
    }
    const c1 = 1 - Math.pow(b1, it);
    const c2 = 1 - Math.pow(b2, it);
    for (let i = 0; i < n; i++) {
      if (!active[i]) continue;
      ma[i] = b1 * ma[i] + (1 - b1) * ga[i];
      va[i] = b2 * va[i] + (1 - b2) * ga[i] * ga[i];
      a[i] = Math.min(0.6, Math.max(0, a[i] - (lr * (ma[i] / c1)) / (Math.sqrt(va[i] / c2) + eps)));
      for (let c = 0; c < 3; c++) {
        const k = i * 3 + c;
        mP[k] = b1 * mP[k] + (1 - b1) * gP[k];
        vP[k] = b2 * vP[k] + (1 - b2) * gP[k] * gP[k];
        Pv[k] = Math.min(a[i], Math.max(0, Pv[k] - (lr * (mP[k] / c1)) / (Math.sqrt(vP[k] / c2) + eps)));
      }
    }
  }
  const O = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) O[i * 3 + c] = a[i] > 1e-6 ? Pv[i * 3 + c] / a[i] : 0.5;

  // Räumlich glätten, dann Maske anwenden (unbetroffen = exakt 0).
  let aS = gaussBlur(a, gw, gh, 1.0);
  const P = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) P[i * 3 + c] = a[i] * O[i * 3 + c];
  const PS = gaussBlur(P, gw, gh, 1.0, 3);
  const Os = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) Os[i * 3 + c] = aS[i] > 1e-6 ? Math.min(1, PS[i * 3 + c] / aS[i]) : 0.5;
  for (let i = 0; i < n; i++) aS[i] = mask[i] * aS[i];
  limitSlope(aS, gw, gh, opt.maxStep);

  // Keine Überkorrektur: Farbrichtung darf nicht kippen.
  const out = [0, 0, 0];
  for (let i = 0; i < n; i++) {
    if (aS[i] <= 0) continue;
    for (let tries = 0; tries < 30; tries++) {
      let flipped = false;
      for (let l = 0; l < model.levels.length && !flipped; l++) {
        const g = model.g[l];
        const ref = model.ref[l];
        const before = chromaVector([g[i * 3] * ref[0], g[i * 3 + 1] * ref[1], g[i * 3 + 2] * ref[2]], ref);
        predict(g, i * 3, model.levels[l], aS[i], Os.subarray(i * 3, i * 3 + 3), out);
        const after = chromaVector([out[0] * ref[0], out[1] * ref[1], out[2] * ref[2]], ref);
        const dot = before[0] * after[0] + before[1] * after[1];
        // Kippen unterhalb der Rauschgrenze ist nicht sichtbar und wird toleriert.
        if (dot < 0 && Math.hypot(after[0], after[1]) > model.t0) flipped = true;
      }
      if (!flipped) break;
      aS[i] *= 0.9;
    }
  }
  limitSlope(aS, gw, gh, opt.maxStep);

  // Werte unter einem halben 8-Bit-Schritt sind exakt 0.
  const Pout = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    if (aS[i] < 0.5 / 255) aS[i] = 0;
    for (let c = 0; c < 3; c++) Pout[i * 3 + c] = aS[i] * Os[i * 3 + c];
  }
  return { gw, gh, a: aS, P: Pout };
}

/** Vorhergesagter Farbabstand je Zelle bei Stufe l mit Karte (optional skaliert). */
export function predictedError(model: PanelModel, map: CorrectionMap | null, l: number, strength = 1): Float32Array {
  const { gw, gh } = model;
  const g = model.g[l];
  const ref = model.ref[l];
  const out = new Float32Array(gw * gh);
  const p = [0, 0, 0];
  const O = [0, 0, 0];
  for (let i = 0; i < gw * gh; i++) {
    let a = 0;
    if (map && map.a[i] > 0) {
      a = Math.min(1, map.a[i] * strength);
      for (let c = 0; c < 3; c++) O[c] = map.P[i * 3 + c] / map.a[i];
    }
    predict(g, i * 3, model.levels[l], a, O, p);
    out[i] = chromaDistance([p[0] * ref[0], p[1] * ref[1], p[2] * ref[2]] as Vec3, ref);
  }
  return out;
}

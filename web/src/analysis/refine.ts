import { chromaDistance, chromaVector, type Vec3 } from './color';
import { gaussBlur, GW, GH } from './grid';
import { cellsToLinear, centerRef, nearestLevel, type Capture, type PanelModel } from './model';
import { predict, type CorrectionMap } from './solve';
import { percentile } from './image';

export interface RoundReport {
  /** 95-%-Wert des Farbabstands im betroffenen Bereich, vorher (Modell ohne Overlay) und nachher (gemessen). */
  before95: number;
  after95: number;
  /** größter Restfehler */
  afterMax: number;
  /** Anteil betroffener Zellen, deren Farbe über die Mitte hinaus gekippt ist */
  overcorrected: number;
  /** stärkste Kante im Restfehler, vorher und nachher */
  edgeBefore: number;
  edgeAfter: number;
  /** alle Abnahmeprüfungen bestanden */
  passed: boolean;
  /** Abbruch: Restfehler klein genug */
  done: boolean;
}

function maxGradient(D: Float32Array, gw: number, gh: number, mask: Float32Array): number {
  let m = 0;
  for (let y = 0; y < gh - 1; y++) for (let x = 0; x < gw - 1; x++) {
    const i = y * gw + x;
    if (mask[i] <= 0 && mask[i + 1] <= 0 && mask[i + gw] <= 0) continue;
    m = Math.max(m, Math.abs(D[i + 1] - D[i]), Math.abs(D[i + gw] - D[i]));
  }
  return m;
}

/**
 * Nachmessen: vergleicht die Messung mit Overlay mit der Vorhersage des Modells
 * und korrigiert das Modell (geschlossener Regelkreis). Gibt das neue Modell und
 * den Prüfbericht zurück.
 */
export function refineModel(
  model: PanelModel,
  map: CorrectionMap,
  strength: number,
  captures: Capture[],
): { model: PanelModel; report: RoundReport } {
  const { gw, gh, mask } = model;
  const n = gw * gh;
  const factorsByLevel: { level: number; f: Float32Array }[] = [];
  const afterAll: number[] = [];
  const beforeAll: number[] = [];
  let afterMax = 0;
  let over = 0;
  let affected = 0;
  let edgeBefore = 0;
  let edgeAfter = 0;
  const p = [0, 0, 0];
  const O = [0, 0, 0];

  for (const cap of captures) {
    const lin = cellsToLinear(cap.cells);
    const ref: Vec3 = centerRef(lin, gw, gh);
    const l = nearestLevel(model, cap.level);
    const g = model.g[l];
    const f = new Float32Array(n * 3).fill(1);
    const Dafter = new Float32Array(n);
    const Dbefore = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const meas: Vec3 = [lin[i * 3] / ref[0], lin[i * 3 + 1] / ref[1], lin[i * 3 + 2] / ref[2]];
      let a = 0;
      if (map.a[i] > 0) {
        a = Math.min(1, map.a[i] * strength);
        for (let c = 0; c < 3; c++) O[c] = map.P[i * 3 + c] / map.a[i];
      }
      predict(g, i * 3, cap.level, a, O, p);
      if (mask[i] > 0) for (let c = 0; c < 3; c++) f[i * 3 + c] = Math.min(1.25, Math.max(0.8, meas[c] / Math.max(1e-6, p[c])));
      const measRgb: Vec3 = [lin[i * 3], lin[i * 3 + 1], lin[i * 3 + 2]];
      const beforeRgb: Vec3 = [g[i * 3] * ref[0], g[i * 3 + 1] * ref[1], g[i * 3 + 2] * ref[2]];
      Dafter[i] = chromaDistance(measRgb, ref);
      Dbefore[i] = chromaDistance(beforeRgb, ref);
      if (mask[i] > 0.5) {
        affected++;
        afterAll.push(Dafter[i]);
        beforeAll.push(Dbefore[i]);
        afterMax = Math.max(afterMax, Dafter[i]);
        const vb = chromaVector(beforeRgb, ref);
        const va = chromaVector(measRgb, ref);
        if (vb[0] * va[0] + vb[1] * va[1] < 0 && Math.hypot(va[0], va[1]) > model.t0) over++;
      }
    }
    const sb = gaussBlur(Dbefore, gw, gh, 1.5);
    const sa = gaussBlur(Dafter, gw, gh, 1.5);
    edgeBefore = Math.max(edgeBefore, maxGradient(sb, gw, gh, mask));
    edgeAfter = Math.max(edgeAfter, maxGradient(sa, gw, gh, mask));
    factorsByLevel.push({ level: cap.level, f: gaussBlur(f, gw, gh, 2, 3) });
  }

  // Modell korrigieren: jede Stufe mit dem Faktor der nächstgelegenen Messung.
  const g2 = model.g.map((g, l) => {
    const L = model.levels[l];
    let best = factorsByLevel[0];
    for (const fl of factorsByLevel) if (Math.abs(fl.level - L) < Math.abs(best.level - L)) best = fl;
    const out = new Float32Array(g.length);
    for (let k = 0; k < g.length; k++) out[k] = g[k] * best.f[k];
    return out;
  });

  const before95 = beforeAll.length ? percentile(beforeAll, 0.95) : 0;
  const after95 = afterAll.length ? percentile(afterAll, 0.95) : 0;
  const overcorrected = affected ? over / affected : 0;
  const passed = overcorrected < 0.02 && edgeAfter <= edgeBefore * 1.05 + 0.1;
  return {
    model: { ...model, g: g2 },
    report: {
      before95,
      after95,
      afterMax,
      overcorrected,
      edgeBefore,
      edgeAfter,
      passed,
      done: passed && after95 < 2,
    },
  };
}

export { GW, GH };

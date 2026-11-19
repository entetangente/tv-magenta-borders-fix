import { buildModel, type Capture, type PanelModel } from './model';
import { DEFAULT_SOLVE, predictedError, solveMap, type CorrectionMap, type SolveOptions } from './solve';
import { percentile } from './image';

/** Drei Karten mit unterschiedlichem Schwarz-Schutz: niedrig, mittel, hoch. */
export const LAMBDAS = [10, 200, 1000];

/** Messbilder der ersten Runde (kodierter Grauwert). */
export const CAPTURE_LEVELS = [0.75, 0.5, 0.35, 0.25, 0.15];
/** Messbilder beim Nachmessen. */
export const REFINE_LEVELS = [0.75, 0.25];

export interface MapSet {
  maps: CorrectionMap[];
  /** 95-%-Wert des Farbabstands im betroffenen Bereich bei der hellsten Stufe */
  before95: number;
  after95: number[];
  /** Anteil der Zellen mit Korrektur */
  coverage: number;
}

export function computeMaps(model: PanelModel, opt: Partial<SolveOptions> = {}, lambdas = LAMBDAS): MapSet {
  const maps = lambdas.map((lambda) => solveMap(model, { ...DEFAULT_SOLVE, ...opt, lambda }));
  const affected: number[] = [];
  for (let i = 0; i < model.mask.length; i++) if (model.mask[i] > 0.5) affected.push(i);
  const pick = (D: Float32Array) => (affected.length ? percentile(affected.map((i) => D[i]), 0.95) : 0);
  const before95 = pick(predictedError(model, null, 0));
  const after95 = maps.map((m) => pick(predictedError(model, m, 0)));
  const coverage = maps[1].a.reduce((s, v) => s + (v > 0 ? 1 : 0), 0) / maps[1].a.length;
  return { maps, before95, after95, coverage };
}

export function modelFromCaptures(captures: Capture[]): PanelModel {
  return buildModel(captures);
}

import type { PanelModel } from './model';
import { DEFAULT_SOLVE, predictedError, solveMap, type CorrectionMap, type SolveOptions } from './solve';
import { percentile } from './image';

/**
 * Drei Karten für „Schutz für dunkle Szenen“: niedrig, mittel, hoch.
 * Ein Overlay kann nur mischen: Je kräftiger es bei Weiß korrigiert, desto
 * mehr färbt es dunkle Grautöne grünlich. Die Varianten wählen verschiedene
 * Punkte auf dieser Abwägung.
 */
export const VARIANTS: Partial<SolveOptions>[] = [
  { levelPower: 4, guardFrom: 0.6, beta: 0.9, lambda: 10 }, // niedrig: kräftig bei hellen Szenen
  { levelPower: 3, guardFrom: 0.4, beta: 0.85, lambda: 50 }, // mittel
  { levelPower: 2, guardFrom: 0, beta: 0.75, lambda: 500 }, // hoch: vorsichtig
];

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

export function computeMaps(model: PanelModel, opt: Partial<SolveOptions> = {}, variants = VARIANTS): MapSet {
  const maps = variants.map((v) => solveMap(model, { ...DEFAULT_SOLVE, ...v, ...opt }));
  const affected: number[] = [];
  for (let i = 0; i < model.mask.length; i++) if (model.mask[i] > 0.5) affected.push(i);
  const pick = (D: Float32Array) => (affected.length ? percentile(affected.map((i) => D[i]), 0.95) : 0);
  const before95 = pick(predictedError(model, null, 0));
  const after95 = maps.map((m) => pick(predictedError(model, m, 0)));
  const coverage = maps[1].a.reduce((s, v) => s + (v > 0 ? 1 : 0), 0) / maps[1].a.length;
  return { maps, before95, after95, coverage };
}

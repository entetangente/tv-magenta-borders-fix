/// <reference lib="webworker" />
import { analyzePhoto, ISSUE_TEXT } from './analysis/measure';
import { buildModel, type Capture, type PanelModel } from './analysis/model';
import { computeMaps } from './analysis/pipeline';
import { refineModel } from './analysis/refine';
import { encodeMaps } from './analysis/mapfile';
import type { CorrectionMap } from './analysis/solve';
import type { RGBAImage } from './analysis/image';

/** Zustand bleibt im Worker, damit Fotos und Modell nicht hin- und herkopiert werden. */
let captures: Capture[] = [];
let model: PanelModel | null = null;
let maps: CorrectionMap[] = [];

export type WorkerRequest =
  | { id: number; cmd: 'analyze'; level: number; img: RGBAImage; refine?: boolean }
  | { id: number; cmd: 'reset' }
  | { id: number; cmd: 'build' }
  | { id: number; cmd: 'refine'; strength: number; variant: number };

const refineCaptures: Capture[] = [];

self.onmessage = (e: MessageEvent<WorkerRequest>) => {
  const req = e.data;
  const reply = (data: object, transfer: Transferable[] = []) =>
    (self as unknown as Worker).postMessage({ id: req.id, ...data }, transfer);
  try {
    switch (req.cmd) {
      case 'reset':
        captures = [];
        refineCaptures.length = 0;
        model = null;
        maps = [];
        reply({ ok: true });
        break;
      case 'analyze': {
        const r = analyzePhoto(req.img);
        if (r.ok && r.cells) {
          const list = req.refine ? refineCaptures : captures;
          const idx = list.findIndex((c) => c.level === req.level);
          if (idx >= 0) list.splice(idx, 1);
          list.push({ level: req.level, cells: r.cells });
        }
        reply({ ok: r.ok, issues: r.issues.map((c) => ISSUE_TEXT[c]), corners: r.detection?.corners ?? null });
        break;
      }
      case 'build': {
        model = buildModel(captures);
        const set = computeMaps(model);
        maps = set.maps;
        const buf = encodeMaps(maps);
        reply({ ok: true, buf, before95: set.before95, after95: set.after95, coverage: set.coverage, t0: model.t0 }, [buf]);
        break;
      }
      case 'refine': {
        if (!model || maps.length === 0) throw new Error('Noch keine Karte berechnet');
        const { model: m2, report } = refineModel(model, maps[req.variant], req.strength, refineCaptures);
        refineCaptures.length = 0;
        let buf: ArrayBuffer | null = null;
        if (report.passed) {
          model = m2;
          const set = computeMaps(model);
          maps = set.maps;
          buf = encodeMaps(maps);
        }
        reply({ ok: true, report, buf }, buf ? [buf] : []);
        break;
      }
    }
  } catch (err) {
    reply({ ok: false, error: String((err as Error)?.message ?? err) });
  }
};

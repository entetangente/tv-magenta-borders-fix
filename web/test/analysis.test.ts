import { describe, expect, it } from 'vitest';
import { analyzePhoto } from '../src/analysis/measure';
import { buildModel, type Capture } from '../src/analysis/model';
import { CAPTURE_LEVELS, REFINE_LEVELS, computeMaps } from '../src/analysis/pipeline';
import { refineModel } from '../src/analysis/refine';
import { decodeMaps, encodeMaps } from '../src/analysis/mapfile';
import { GW, GH, isCenterCell } from '../src/analysis/grid';
import { DEFAULT_CORNERS, synthPhoto } from './synth';
import type { CorrectionMap } from '../src/analysis/solve';

function capture(level: number, extra = {}): Capture {
  const r = analyzePhoto(synthPhoto({ level, seed: Math.round(level * 1000), ...extra }));
  expect(r.issues).toEqual([]);
  return { level, cells: r.cells! };
}

describe('Fotoprüfung', () => {
  it('findet die Ecken genau', () => {
    const r = analyzePhoto(synthPhoto());
    expect(r.ok).toBe(true);
    r.detection!.corners.forEach((c, i) => {
      expect(Math.hypot(c.x - DEFAULT_CORNERS[i].x, c.y - DEFAULT_CORNERS[i].y)).toBeLessThan(1.5);
    });
  });

  it('erkennt angeschnittenen Bildschirm', () => {
    const r = analyzePhoto(synthPhoto({ corners: [{ x: -40, y: 200 }, { x: 1400, y: 210 }, { x: 1420, y: 900 }, { x: -40, y: 920 }] }));
    expect(r.issues).toContain('cropped');
  });

  it('erkennt zu großen Abstand', () => {
    const r = analyzePhoto(synthPhoto({ corners: [{ x: 600, y: 500 }, { x: 1000, y: 500 }, { x: 1000, y: 725 }, { x: 600, y: 725 }] }));
    expect(r.issues).toContain('too_far');
  });

  it('erkennt Weitwinkel-Verzeichnung', () => {
    const r = analyzePhoto(synthPhoto({ barrel: 0.12 }));
    expect(r.issues).toContain('wide_angle');
  });

  it('erkennt Unschärfe', () => {
    const r = analyzePhoto(synthPhoto({ blur: 10 }));
    expect(r.issues).toContain('blurry');
  });

  it('erkennt Überbelichtung', () => {
    const r = analyzePhoto(synthPhoto({ exposure: 3 }));
    expect(r.issues).toContain('overexposed');
  });
});

describe('Korrekturkarte', () => {
  const captures = CAPTURE_LEVELS.map((L) => capture(L));
  const model = buildModel(captures);
  const set = computeMaps(model);

  it('Maske ist in der Mitte exakt 0 und am Rand aktiv', () => {
    for (let y = 0; y < GH; y++) for (let x = 0; x < GW; x++) {
      if (isCenterCell(x, y)) for (const m of set.maps) expect(m.a[y * GW + x]).toBe(0);
    }
    const leftMid = (GH >> 1) * GW;
    expect(model.mask[leftMid]).toBeGreaterThan(0.9);
    expect(set.maps[1].a[leftMid]).toBeGreaterThan(0.02);
  });

  it('verringert den Fehler deutlich (Vorhersage)', () => {
    console.log('vorher', set.before95.toFixed(2), 'nachher', set.after95.map((v) => v.toFixed(2)), 'Abdeckung', set.coverage.toFixed(3), 't0', model.t0.toFixed(2));
    // Ein Overlay kann nur mischen: Ohne Überkorrektur in dunklen Szenen ist
    // bei Weiß nur ein Teil des Fehlers ausgleichbar (siehe Spezifikation, Grenzen).
    expect(set.after95[0]).toBeLessThan(set.before95 * 0.85);
    // mehr Schwarz-Schutz = weniger Wirkung
    expect(set.after95[2]).toBeGreaterThanOrEqual(set.after95[0] - 0.05);
  });

  it('hält die Steigungsgrenze ein', () => {
    for (const m of set.maps) {
      for (let y = 0; y < GH; y++) for (let x = 0; x < GW - 1; x++) {
        expect(Math.abs(m.a[y * GW + x + 1] - m.a[y * GW + x])).toBeLessThanOrEqual(0.01 + 1e-6);
      }
    }
  });

  it('Kartenformat übersteht Hin- und Rückweg', () => {
    const back = decodeMaps(encodeMaps(set.maps));
    expect(back.length).toBe(3);
    expect(Array.from(back[1].a)).toEqual(Array.from(set.maps[1].a));
  });

  it('Nachmessen: echte Wirkung bestätigt Verbesserung, Prüfungen bestehen', () => {
    let m = model;
    let map: CorrectionMap = set.maps[1];
    let last = Infinity;
    for (let round = 0; round < 2; round++) {
      const caps = REFINE_LEVELS.map((L) => capture(L, { map, strength: 1, seed: 77 + round }));
      const { model: m2, report } = refineModel(m, map, 1, caps);
      console.log('Runde', round + 1, JSON.stringify(report, (_, v) => (typeof v === 'number' ? +v.toFixed(3) : v)));
      expect(report.after95).toBeLessThan(report.before95);
      expect(report.overcorrected).toBeLessThan(0.02);
      expect(report.passed).toBe(true);
      expect(report.after95).toBeLessThanOrEqual(last + 0.3);
      last = report.after95;
      m = m2;
      map = computeMaps(m).maps[1];
    }
  });
});

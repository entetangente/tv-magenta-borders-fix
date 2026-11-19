import { detectScreen, type Detection } from './detect';
import { unitToQuad, applyH } from './homography';
import { median, sampleRGB, type RGBAImage } from './image';
import { GW, GH } from './grid';

export type IssueCode =
  | 'no_screen'
  | 'cropped'
  | 'too_far'
  | 'wide_angle'
  | 'blurry'
  | 'overexposed'
  | 'too_dark'
  | 'unclear_edges';

export const ISSUE_TEXT: Record<IssueCode, string> = {
  no_screen: 'Bildschirm nicht gefunden. Raum abdunkeln und den ganzen Fernseher ins Bild nehmen.',
  cropped: 'Bildschirm nicht vollständig im Bild. Etwas weiter weg gehen.',
  too_far: 'Zu weit weg. Der Bildschirm sollte das Foto möglichst ausfüllen.',
  wide_angle: 'Bitte die normale Kamera (1×) verwenden, nicht Weitwinkel.',
  blurry: 'Verwackelt oder unscharf. Handy ruhig halten und erneut versuchen.',
  overexposed: 'Foto ist zu hell. Raumlicht ausschalten und erneut versuchen.',
  too_dark: 'Foto ist zu dunkel. Erneut versuchen.',
  unclear_edges: 'Bildränder nicht klar erkennbar. Gerade von vorn fotografieren.',
};

export interface PhotoResult {
  ok: boolean;
  issues: IssueCode[];
  detection: Detection | null;
  /** Kodierte Farbwerte 0–1 je Zelle (GW×GH×3), Median der Abtastpunkte. */
  cells: Float32Array | null;
}

/**
 * Tastet den entzerrten Bildschirm auf einem GW×GH-Raster ab (k×k Punkte je
 * Zelle mit Abstand zum Zellrand) und nimmt den Median je Kanal.
 */
export function sampleGrid(img: RGBAImage, det: Detection, gw = GW, gh = GH, k = 4, inset = 0.2): { cells: Float32Array; clipped: number } {
  const H = unitToQuad(det.corners);
  const cells = new Float32Array(gw * gh * 3);
  const tmp = new Float32Array(3);
  const r: number[] = [];
  const g: number[] = [];
  const b: number[] = [];
  let clipped = 0;
  let total = 0;
  for (let cy = 0; cy < gh; cy++) {
    for (let cx = 0; cx < gw; cx++) {
      r.length = g.length = b.length = 0;
      for (let j = 0; j < k; j++) {
        for (let i = 0; i < k; i++) {
          const u = (cx + inset + ((1 - 2 * inset) * (i + 0.5)) / k) / gw;
          const v = (cy + inset + ((1 - 2 * inset) * (j + 0.5)) / k) / gh;
          const p = applyH(H, u, v);
          sampleRGB(img, p.x, p.y, tmp);
          r.push(tmp[0]); g.push(tmp[1]); b.push(tmp[2]);
          total++;
          if (tmp[0] >= 250 || tmp[1] >= 250 || tmp[2] >= 250) clipped++;
        }
      }
      const o = (cy * gw + cx) * 3;
      cells[o] = median(r) / 255;
      cells[o + 1] = median(g) / 255;
      cells[o + 2] = median(b) / 255;
    }
  }
  return { cells, clipped: clipped / total };
}

/** Prüft ein Foto und liefert bei Erfolg die Rasterwerte. */
export function analyzePhoto(img: RGBAImage): PhotoResult {
  const issues: IssueCode[] = [];
  const { detection: det, touchesBorder } = detectScreen(img);
  if (!det) return { ok: false, issues: [touchesBorder ? 'cropped' : 'no_screen'], detection: null, cells: null };

  const { width: w, height: h } = img;
  const margin = 0.01 * Math.max(w, h);
  const outside = det.corners.some((c) => c.x < margin || c.y < margin || c.x > w - margin || c.y > h - margin);
  // Nur die gefundenen Ecken zählen. Eine helle Fläche am Fotorand (Wand,
  // Spiegelung) ist kein Grund, das Foto abzulehnen.
  if (outside) issues.push('cropped');
  else if (det.areaFraction < 0.15) issues.push('too_far');

  if (det.edges.some((e) => e.bow > 0.004)) issues.push('wide_angle');
  if (det.edges.some((e) => e.inlierFraction < 0.6)) issues.push('unclear_edges');

  const screenW = Math.hypot(det.corners[1].x - det.corners[0].x, det.corners[1].y - det.corners[0].y);
  const width = Math.max(...det.edges.map((e) => e.edgeWidth));
  if (width > Math.max(4, 0.012 * screenW)) issues.push('blurry');

  if (issues.includes('cropped')) return { ok: false, issues, detection: det, cells: null };
  const { cells, clipped } = sampleGrid(img, det);
  if (clipped > 0.002) issues.push('overexposed');
  // Helligkeit der Mitte
  const mid = ((GH >> 1) * GW + (GW >> 1)) * 3;
  if (cells[mid + 1] < 0.08) issues.push('too_dark');

  return { ok: issues.length === 0, issues, detection: det, cells };
}

import './style.css';
import { TvLink } from './net';
import { CAPTURE_LEVELS, REFINE_LEVELS } from './analysis/pipeline';
import type { WorkerRequest } from './worker';
import type { RoundReport } from './analysis/refine';

// ---------- Infrastruktur ----------

const app = document.getElementById('app')!;
const dots = document.getElementById('dots')!;
const tv = new TvLink();
const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
let nextId = 1;
const pending = new Map<number, (v: any) => void>();
worker.onmessage = (e) => {
  const cb = pending.get(e.data.id);
  pending.delete(e.data.id);
  cb?.(e.data);
};
type DistributiveOmit<T, K extends keyof any> = T extends unknown ? Omit<T, K> : never;
function call(req: DistributiveOmit<WorkerRequest, 'id'>, transfer: Transferable[] = []): Promise<any> {
  const id = nextId++;
  return new Promise((res) => {
    pending.set(id, res);
    worker.postMessage({ ...req, id }, transfer);
  });
}

function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> & { cls?: string } = {},
  ...children: (Node | string | null | false)[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  const { cls, ...rest } = props as any;
  if (cls) el.className = cls;
  Object.assign(el, rest);
  for (const c of children) if (c) el.append(c);
  return el;
}

const STEPS = ['verbunden', 'vorbereitung', 'aufnahmen', 'berechnen', 'ergebnis'] as const;
type Step = (typeof STEPS)[number];
function setStep(step: Step | 'genauer' | 'fertig'): void {
  const idx = step === 'genauer' || step === 'fertig' ? STEPS.length : STEPS.indexOf(step);
  dots.replaceChildren(...STEPS.map((_, i) => h('span', { cls: i <= idx ? 'on' : '' })));
}

function screen(step: Parameters<typeof setStep>[0], ...children: (Node | string | null | false)[]): void {
  setStep(step);
  app.replaceChildren(...children.filter(Boolean) as Node[]);
  window.scrollTo(0, 0);
}

// ---------- Foto aufnehmen und einlesen ----------

/** Öffnet die Kamera-App des Handys (ohne HTTPS möglich). */
function takePhoto(): Promise<File | null> {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept: 'image/*' });
    input.setAttribute('capture', 'environment');
    input.style.display = 'none';
    input.onchange = () => {
      resolve(input.files?.[0] ?? null);
      input.remove();
    };
    document.body.append(input);
    input.click();
  });
}

/** Foto laden und auf höchstens 2000 px Breite verkleinern. */
async function loadImage(file: File): Promise<{ width: number; height: number; data: Uint8ClampedArray; thumb: string }> {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 2000 / Math.max(bmp.width, bmp.height));
  const w = Math.round(bmp.width * scale);
  const hgt = Math.round(bmp.height * scale);
  const canvas = h('canvas', { width: w, height: hgt });
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, w, hgt);
  bmp.close?.();
  const id = ctx.getImageData(0, 0, w, hgt);
  // kleine Vorschau für Rückmeldungen bei abgelehnten Fotos
  const t = h('canvas', { width: 480, height: Math.round((480 * hgt) / w) });
  t.getContext('2d')!.drawImage(canvas, 0, 0, t.width, t.height);
  return { width: w, height: hgt, data: id.data, thumb: t.toDataURL('image/jpeg', 0.7) };
}

// ---------- Ablauf ----------

let strength = 1;
let variant = 1;

function connectedScreen(): void {
  const status = h('p', {}, 'Verbinde mit dem Fernseher …');
  const btn = h('button', { cls: 'primary', disabled: true, onclick: prepScreen }, 'Weiter');
  const update = () => {
    const s = tv.state;
    if (s) {
      status.replaceChildren(h('span', { cls: 'ok' }, '✓ Verbunden'), ` mit ${s.device}`);
      btn.disabled = false;
    }
  };
  tv.onState = update;
  update();
  screen(
    'verbunden',
    h('h1', {}, 'Kalibrierung mit dem Handy'),
    h('p', {}, 'Du fotografierst gleich einige Testbilder vom Fernseher ab. Daraus berechnet das Handy die Korrektur. Die Fotos verlassen dein Handy nicht.'),
    h('div', { cls: 'card' }, status),
    h('div', { cls: 'spacer' }),
    btn,
  );
}

function prepScreen(): void {
  const items = [
    'Licht aus oder stark gedimmt',
    'Am üblichen Sitzplatz Platz genommen',
    'Fernseher läuft seit mindestens 30 Minuten',
  ];
  const btn = h('button', { cls: 'primary', disabled: true, onclick: () => captureScreen(0) }, 'Los geht’s');
  const boxes = items.map(() => {
    const cb = h('input', { type: 'checkbox' });
    cb.onchange = () => (btn.disabled = !boxes.every((b) => b.checked));
    return cb;
  });
  tv.send({ t: 'step', text: 'Vorbereitung' });
  screen(
    'vorbereitung',
    h('h1', {}, 'Vorbereitung'),
    h('p', {}, 'Für ein genaues Ergebnis bitte kurz abhaken:'),
    ...items.map((t, i) => h('label', { cls: 'check' }, boxes[i], t)),
    h('div', { cls: 'spacer' }),
    btn,
  );
}

/** Ein Foto pro Testbild. refine = Nachmess-Runde mit eingeschalteter Korrektur. */
function captureScreen(index: number, refine = false): void {
  const levels = refine ? REFINE_LEVELS : CAPTURE_LEVELS;
  const level = levels[index];
  const n = levels.length;
  const hint = `Foto ${index + 1} von ${n}`;
  tv.send({ t: 'show', level, hint, overlay: refine ? 'preview' : 'off' });
  if (refine) tv.send({ t: 'tune', strength, variant });
  const msg = h('p', {}, 'Ganzen Bildschirm ins Bild nehmen, gerade von vorn, normale Kamera (1×).');
  const btn = h('button', { cls: 'primary' }, 'Foto aufnehmen');
  btn.onclick = async () => {
    const file = await takePhoto();
    if (!file) return;
    btn.disabled = true;
    msg.replaceChildren('Prüfe Foto …');
    try {
      const { thumb, ...img } = await loadImage(file);
      const r = await call({ cmd: 'analyze', level, img, refine }, [img.data.buffer]);
      if (r.ok) {
        if (index + 1 < n) captureScreen(index + 1, refine);
        else if (refine) refineScreen();
        else computeScreen();
        return;
      }
      msg.replaceChildren(
        h('span', { cls: 'err' }, r.issues?.[0] ?? r.error ?? 'Foto unbrauchbar.'),
        preview(thumb, img.width, img.height, r.corners),
        h('small', {}, r.corners ? 'Grün: hier vermutet die App den Bildschirm.' : 'Die App hat keinen Bildschirm gefunden.'),
      );
      btn.textContent = 'Nochmal versuchen';
    } catch (err) {
      msg.replaceChildren(h('span', { cls: 'err' }, `Foto konnte nicht gelesen werden: ${(err as Error).message}`));
    }
    btn.disabled = false;
  };
  screen(
    refine ? 'genauer' : 'aufnahmen',
    h('h1', {}, refine ? `Nachmessen: ${hint}` : hint),
    h('div', { cls: 'progress' }, h('div', { style: `width:${(index / n) * 100}%` } as any)),
    h('div', { cls: 'card' }, h('div', { cls: 'big' }, '📷'), msg),
    h('div', { cls: 'spacer' }),
    btn,
  );
}

/** Foto mit eingezeichnetem, erkanntem Bildschirmumriss. */
function preview(src: string, w: number, hgt: number, corners: { x: number; y: number }[] | null): HTMLElement {
  const box = h('div', { cls: 'preview' });
  box.style.position = 'relative';
  box.append(h('img', { src, cls: 'thumb' }));
  if (corners) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', `0 0 ${w} ${hgt}`);
    svg.setAttribute('style', 'position:absolute;inset:0;width:100%;height:100%');
    const poly = document.createElementNS(ns, 'polygon');
    poly.setAttribute('points', corners.map((c) => `${c.x},${c.y}`).join(' '));
    poly.setAttribute('fill', 'none');
    poly.setAttribute('stroke', '#8bd17c');
    poly.setAttribute('stroke-width', String(Math.max(w, hgt) / 200));
    svg.append(poly);
    box.append(svg);
  }
  return box;
}

async function computeScreen(): Promise<void> {
  const bar = h('div');
  screen(
    'berechnen',
    h('h1', {}, 'Berechne Korrektur …'),
    h('div', { cls: 'progress' }, bar),
    h('p', {}, 'Einige Sekunden. Bitte die Seite offen lassen.'),
  );
  let p = 5;
  const timer = setInterval(() => (bar.style.width = `${(p = Math.min(95, p + 4))}%`), 300);
  tv.send({ t: 'step', text: 'Berechne Korrektur …' });
  const r = await call({ cmd: 'build' });
  clearInterval(timer);
  if (!r.ok) return errorScreen(r.error);
  try {
    await tv.uploadMap(r.buf, { before95: r.before95, after95: r.after95, coverage: r.coverage, t0: r.t0 });
  } catch (err) {
    return errorScreen((err as Error).message);
  }
  resultScreen({ before: r.before95, after: r.after95[variant], coverage: r.coverage });
}

function errorScreen(text: string): void {
  screen(
    'berechnen',
    h('h1', {}, 'Das hat nicht geklappt'),
    h('div', { cls: 'card' }, h('p', { cls: 'err' }, text)),
    h('div', { cls: 'spacer' }),
    h('button', { cls: 'primary', onclick: () => location.reload() }, 'Neu starten'),
  );
}

function seg(options: string[], selected: number, onPick: (i: number) => void): HTMLElement {
  const el = h('div', { cls: 'seg' });
  const render = (sel: number) =>
    el.replaceChildren(
      ...options.map((o, i) =>
        h('button', { cls: i === sel ? 'on' : '', onclick: () => { render(i); onPick(i); } }, o),
      ),
    );
  render(selected);
  return el;
}

function resultScreen(stats: { before: number; after: number; coverage: number }, report?: RoundReport): void {
  let pattern = 1;
  const show = () => tv.send({ t: 'show', level: pattern, overlay: 'preview' });
  show();
  tv.send({ t: 'tune', strength, variant });

  const sLabel = h('span', {}, `${Math.round(strength * 100)} %`);
  const range = h('input', { type: 'range', min: '0', max: '150', step: '5', value: String(Math.round(strength * 100)) });
  range.oninput = () => {
    strength = Number(range.value) / 100;
    sLabel.textContent = `${range.value} %`;
    tv.send({ t: 'tune', strength, variant });
  };

  const info = report
    ? h('p', {}, report.passed
        ? `Nachgemessen: Restabweichung ${report.after95.toFixed(1)} (vorher ${report.before95.toFixed(1)}). ${report.done ? 'Sehr gut, fertig.' : ''}`
        : 'Die Nachmessung zeigt Nebenwirkungen. Die Stärke wurde deshalb verringert.')
    : h('p', {}, `Der Fernseher zeigt jetzt Weiß mit Korrektur. Geschätzte Verbesserung am Rand: ${stats.before.toFixed(1)} → ${stats.after.toFixed(1)} (ΔE).`);

  screen(
    'ergebnis',
    h('h1', {}, 'Ergebnis'),
    info,
    h('div', { cls: 'card' },
      h('strong', {}, 'Vergleich'),
      seg(['Nachher', 'Vorher'], 0, (i) => tv.send({ t: 'compare', on: i === 1 })),
      h('strong', {}, 'Testbild'),
      seg(['Weiß', 'Grau', 'Dunkel', 'Schwarz'], 0, (i) => { pattern = [1, 0.5, 0.15, 0][i]; show(); }),
    ),
    h('div', { cls: 'card' },
      h('div', { cls: 'slider-label' }, h('strong', {}, 'Stärke'), sLabel),
      range,
      h('strong', {}, 'Schutz für dunkle Szenen'),
      seg(['Niedrig', 'Mittel', 'Hoch'], variant, (i) => { variant = i; tv.send({ t: 'tune', strength, variant }); }),
      h('small', {}, 'Höherer Schutz: weniger Schimmer in dunklen Szenen, dafür schwächere Wirkung.'),
    ),
    h('button', { cls: 'primary', onclick: applyScreen }, 'Übernehmen'),
    h('button', { onclick: () => captureScreen(0, true) }, 'Genauer machen (2 Fotos)'),
  );
}

async function refineScreen(): Promise<void> {
  screen('genauer', h('h1', {}, 'Werte Nachmessung aus …'), h('div', { cls: 'progress' }, h('div', { style: 'width:60%' } as any)));
  const r = await call({ cmd: 'refine', strength, variant });
  if (!r.ok) return errorScreen(r.error);
  const report: RoundReport = r.report;
  if (r.buf) {
    try {
      await tv.uploadMap(r.buf, { report });
    } catch (err) {
      return errorScreen((err as Error).message);
    }
  } else {
    // Abnahme nicht bestanden: abschwächen statt verstärken.
    strength = Math.max(0.3, strength * 0.85);
  }
  resultScreen({ before: report.before95, after: report.after95, coverage: 0 }, report);
}

function applyScreen(): void {
  tv.send({ t: 'compare', on: false });
  tv.send({ t: 'tune', strength, variant });
  tv.send({ t: 'apply' });
  tv.send({ t: 'show', level: null, overlay: 'active' });
  screen(
    'fertig',
    h('h1', {}, 'Fertig ✓'),
    h('p', {}, 'Die Korrektur ist gespeichert und aktiv. Du kannst die Seite schließen. Am Fernseher lässt sich die Stärke jederzeit unter „Feinabstimmung“ ändern.'),
    h('div', { cls: 'spacer' }),
    h('button', { onclick: () => { call({ cmd: 'reset' }); prepScreen(); } }, 'Neu kalibrieren'),
  );
}

// ---------- Start ----------

tv.onConnection = (ok) => {
  if (!ok && !tv.state) app.querySelector('p')?.replaceChildren('Keine Verbindung. Sind Handy und Fernseher im selben WLAN?');
};
tv.connect();
connectedScreen();

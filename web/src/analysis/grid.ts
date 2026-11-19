/** Gemeinsame Rasterkonstanten und Hilfsfunktionen für 160×90-Karten. */
/**
 * Rasterauflösung der Korrekturkarte. 384×216 ergibt bei 1080p etwa 5 Pixel je
 * Zelle; lila Ränder sind oft nur 1–2 % der Bildhöhe breit.
 */
export const GW = 384;
export const GH = 216;

/** Zellen der unbetroffenen Bildmitte (Referenz). */
export function isCenterCell(x: number, y: number, gw = GW, gh = GH): boolean {
  return x >= gw * 0.35 && x < gw * 0.65 && y >= gh * 0.35 && y < gh * 0.65;
}

/** Gauß-Glättung eines Rasters (separierbar, Ränder gespiegelt). */
export function gaussBlur(src: Float32Array, gw: number, gh: number, sigma: number, channels = 1): Float32Array {
  if (sigma <= 0) return src.slice();
  const r = Math.ceil(sigma * 3);
  const k = new Float32Array(2 * r + 1);
  let s = 0;
  for (let i = -r; i <= r; i++) { k[i + r] = Math.exp(-(i * i) / (2 * sigma * sigma)); s += k[i + r]; }
  for (let i = 0; i < k.length; i++) k[i] /= s;
  const clampI = (v: number, n: number) => (v < 0 ? -v - 1 : v >= n ? 2 * n - v - 1 : v);
  const tmp = new Float32Array(src.length);
  const out = new Float32Array(src.length);
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) for (let c = 0; c < channels; c++) {
    let a = 0;
    for (let i = -r; i <= r; i++) a += k[i + r] * src[(y * gw + Math.min(gw - 1, Math.max(0, clampI(x + i, gw)))) * channels + c];
    tmp[(y * gw + x) * channels + c] = a;
  }
  for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) for (let c = 0; c < channels; c++) {
    let a = 0;
    for (let i = -r; i <= r; i++) a += k[i + r] * tmp[(Math.min(gh - 1, Math.max(0, clampI(y + i, gh))) * gw + x) * channels + c];
    out[(y * gw + x) * channels + c] = a;
  }
  return out;
}

export function smoothstep(e0: number, e1: number, x: number): number {
  if (x <= e0) return 0;
  if (x >= e1) return 1;
  const t = (x - e0) / (e1 - e0);
  return t * t * (3 - 2 * t);
}

/**
 * Begrenzt die Änderung zwischen Nachbarzellen auf maxStep (Lipschitz-Hülle von
 * unten). Werte werden nur verkleinert, Nullen bleiben Nullen.
 */
export function limitSlope(a: Float32Array, gw: number, gh: number, maxStep: number): void {
  const diag = maxStep * Math.SQRT2;
  for (let pass = 0; pass < 4; pass++) {
    let changed = false;
    for (let y = 0; y < gh; y++) for (let x = 0; x < gw; x++) {
      const i = y * gw + x;
      let v = a[i];
      if (x > 0) v = Math.min(v, a[i - 1] + maxStep);
      if (y > 0) v = Math.min(v, a[i - gw] + maxStep);
      if (x > 0 && y > 0) v = Math.min(v, a[i - gw - 1] + diag);
      if (x < gw - 1 && y > 0) v = Math.min(v, a[i - gw + 1] + diag);
      if (v < a[i]) { a[i] = v; changed = true; }
    }
    for (let y = gh - 1; y >= 0; y--) for (let x = gw - 1; x >= 0; x--) {
      const i = y * gw + x;
      let v = a[i];
      if (x < gw - 1) v = Math.min(v, a[i + 1] + maxStep);
      if (y < gh - 1) v = Math.min(v, a[i + gw] + maxStep);
      if (x < gw - 1 && y < gh - 1) v = Math.min(v, a[i + gw + 1] + diag);
      if (x > 0 && y < gh - 1) v = Math.min(v, a[i + gw - 1] + diag);
      if (v < a[i]) { a[i] = v; changed = true; }
    }
    if (!changed) break;
  }
}

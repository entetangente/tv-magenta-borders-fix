/** Bild im Format von ImageData: RGBA, 8 Bit je Kanal, zeilenweise. */
export interface RGBAImage {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}

export interface Point {
  x: number;
  y: number;
}

/** Helligkeit (0–255) je Pixel, einfache Rec.-601-Gewichtung im kodierten Raum. */
export function luma(img: RGBAImage): Float32Array {
  const { width, height, data } = img;
  const out = new Float32Array(width * height);
  for (let i = 0, j = 0; i < out.length; i++, j += 4) {
    out[i] = 0.299 * data[j] + 0.587 * data[j + 1] + 0.114 * data[j + 2];
  }
  return out;
}

/** Verkleinert ein Einkanalbild um den ganzzahligen Faktor f (Mittelwert je Block). */
export function downscale(src: Float32Array, w: number, h: number, f: number): { data: Float32Array; w: number; h: number } {
  const sw = Math.floor(w / f);
  const sh = Math.floor(h / f);
  const out = new Float32Array(sw * sh);
  const inv = 1 / (f * f);
  for (let y = 0; y < sh; y++) {
    for (let x = 0; x < sw; x++) {
      let s = 0;
      for (let dy = 0; dy < f; dy++) {
        const row = (y * f + dy) * w + x * f;
        for (let dx = 0; dx < f; dx++) s += src[row + dx];
      }
      out[y * sw + x] = s * inv;
    }
  }
  return { data: out, w: sw, h: sh };
}

/** Bilineare Abtastung eines Einkanalbilds. */
export function sample1(src: Float32Array, w: number, h: number, x: number, y: number): number {
  const fx = Math.min(Math.max(x - 0.5, 0), w - 1.001);
  const fy = Math.min(Math.max(y - 0.5, 0), h - 1.001);
  const x0 = Math.floor(fx);
  const y0 = Math.floor(fy);
  const tx = fx - x0;
  const ty = fy - y0;
  const i = y0 * w + x0;
  const a = src[i] + (src[i + 1] - src[i]) * tx;
  const b = src[i + w] + (src[i + w + 1] - src[i + w]) * tx;
  return a + (b - a) * ty;
}

/** Bilineare Abtastung RGB (0–255) an Bildkoordinate (x, y), Pixelmitten bei +0.5. */
export function sampleRGB(img: RGBAImage, x: number, y: number, out: Float32Array, o = 0): void {
  const { width: w, height: h, data } = img;
  const fx = Math.min(Math.max(x - 0.5, 0), w - 1.001);
  const fy = Math.min(Math.max(y - 0.5, 0), h - 1.001);
  const x0 = Math.floor(fx);
  const y0 = Math.floor(fy);
  const tx = fx - x0;
  const ty = fy - y0;
  const i00 = (y0 * w + x0) * 4;
  const i10 = i00 + 4;
  const i01 = i00 + w * 4;
  const i11 = i01 + 4;
  for (let c = 0; c < 3; c++) {
    const a = data[i00 + c] + (data[i10 + c] - data[i00 + c]) * tx;
    const b = data[i01 + c] + (data[i11 + c] - data[i01 + c]) * tx;
    out[o + c] = a + (b - a) * ty;
  }
}

export function median(values: number[] | Float32Array): number {
  const a = Array.from(values).sort((p, q) => p - q);
  const n = a.length;
  if (n === 0) return NaN;
  return n % 2 ? a[(n - 1) >> 1] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
}

export function percentile(values: number[] | Float32Array, p: number): number {
  const a = Array.from(values).sort((x, y) => x - y);
  if (a.length === 0) return NaN;
  const idx = Math.min(a.length - 1, Math.max(0, Math.round((a.length - 1) * p)));
  return a[idx];
}

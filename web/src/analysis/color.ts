/** Farbumrechnungen. Kodierte Werte 0–1, lineares Licht 0–1. */

export const GAMMA = 2.2;

/** Fernseher und Fire TV: einfache Potenzfunktion (Gamma 2.2). */
export function lin(v: number): number {
  return v <= 0 ? 0 : Math.pow(v, GAMMA);
}

export function dlin(v: number): number {
  return v <= 0 ? 0 : GAMMA * Math.pow(v, GAMMA - 1);
}

/** Handykamera: Fotos sind sRGB-kodiert. */
export function srgbToLinear(v: number): number {
  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

export function linearToSrgb(v: number): number {
  const c = Math.min(Math.max(v, 0), 1);
  return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
}

export type Vec3 = [number, number, number];

export function linRgbToXyz(r: number, g: number, b: number): Vec3 {
  return [
    0.4124 * r + 0.3576 * g + 0.1805 * b,
    0.2126 * r + 0.7152 * g + 0.0722 * b,
    0.0193 * r + 0.1192 * g + 0.9505 * b,
  ];
}

const WX = 0.95047;
const WY = 1.0;
const WZ = 1.08883;

function labF(t: number): number {
  return t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116;
}

export function xyzToLab(x: number, y: number, z: number): Vec3 {
  const fx = labF(x / WX);
  const fy = labF(y / WY);
  const fz = labF(z / WZ);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

export function linRgbToLab(r: number, g: number, b: number): Vec3 {
  const [x, y, z] = linRgbToXyz(r, g, b);
  return xyzToLab(x, y, z);
}

/** CIEDE2000-Farbabstand. */
export function deltaE2000(l1: Vec3, l2: Vec3): number {
  const [L1, a1, b1] = l1;
  const [L2, a2, b2] = l2;
  const rad = Math.PI / 180;
  const C1 = Math.hypot(a1, b1);
  const C2 = Math.hypot(a2, b2);
  const Cm = (C1 + C2) / 2;
  const Cm7 = Math.pow(Cm, 7);
  const G = 0.5 * (1 - Math.sqrt(Cm7 / (Cm7 + Math.pow(25, 7))));
  const a1p = (1 + G) * a1;
  const a2p = (1 + G) * a2;
  const C1p = Math.hypot(a1p, b1);
  const C2p = Math.hypot(a2p, b2);
  const h = (b: number, a: number) => {
    if (a === 0 && b === 0) return 0;
    const v = Math.atan2(b, a) / rad;
    return v < 0 ? v + 360 : v;
  };
  const h1p = h(b1, a1p);
  const h2p = h(b2, a2p);
  const dLp = L2 - L1;
  const dCp = C2p - C1p;
  let dhp = 0;
  if (C1p * C2p !== 0) {
    dhp = h2p - h1p;
    if (dhp > 180) dhp -= 360;
    else if (dhp < -180) dhp += 360;
  }
  const dHp = 2 * Math.sqrt(C1p * C2p) * Math.sin((dhp / 2) * rad);
  const Lpm = (L1 + L2) / 2;
  const Cpm = (C1p + C2p) / 2;
  let hpm = h1p + h2p;
  if (C1p * C2p !== 0) {
    if (Math.abs(h1p - h2p) > 180) hpm = h1p + h2p < 360 ? (h1p + h2p + 360) / 2 : (h1p + h2p - 360) / 2;
    else hpm = (h1p + h2p) / 2;
  }
  const T =
    1 -
    0.17 * Math.cos((hpm - 30) * rad) +
    0.24 * Math.cos(2 * hpm * rad) +
    0.32 * Math.cos((3 * hpm + 6) * rad) -
    0.2 * Math.cos((4 * hpm - 63) * rad);
  const dTheta = 30 * Math.exp(-Math.pow((hpm - 275) / 25, 2));
  const Cpm7 = Math.pow(Cpm, 7);
  const Rc = 2 * Math.sqrt(Cpm7 / (Cpm7 + Math.pow(25, 7)));
  const Sl = 1 + (0.015 * Math.pow(Lpm - 50, 2)) / Math.sqrt(20 + Math.pow(Lpm - 50, 2));
  const Sc = 1 + 0.045 * Cpm;
  const Sh = 1 + 0.015 * Cpm * T;
  const Rt = -Math.sin(2 * dTheta * rad) * Rc;
  return Math.sqrt(
    Math.pow(dLp / Sl, 2) + Math.pow(dCp / Sc, 2) + Math.pow(dHp / Sh, 2) + Rt * (dCp / Sc) * (dHp / Sh),
  );
}

/**
 * Reiner Farbabstand zwischen zwei linearen RGB-Farben: Die erste wird vorher
 * auf die Helligkeit (Y) der zweiten skaliert. Damit zählen Helligkeitsabfall
 * zum Rand (Objektiv-Vignettierung, Hintergrundbeleuchtung) nicht als Fehler.
 */
export function chromaDistance(rgb: Vec3, ref: Vec3, refLab?: Vec3): number {
  const y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
  const yr = 0.2126 * ref[0] + 0.7152 * ref[1] + 0.0722 * ref[2];
  if (y <= 1e-6) return 0;
  const s = yr / y;
  const l1 = linRgbToLab(rgb[0] * s, rgb[1] * s, rgb[2] * s);
  const l2 = refLab ?? linRgbToLab(ref[0], ref[1], ref[2]);
  return deltaE2000(l1, l2);
}

/** a*b*-Abweichung zur Referenz bei gleicher Helligkeit (für Richtungsvergleiche). */
export function chromaVector(rgb: Vec3, ref: Vec3): [number, number] {
  const y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
  const yr = 0.2126 * ref[0] + 0.7152 * ref[1] + 0.0722 * ref[2];
  if (y <= 1e-6) return [0, 0];
  const s = yr / y;
  const l1 = linRgbToLab(rgb[0] * s, rgb[1] * s, rgb[2] * s);
  const l2 = linRgbToLab(ref[0], ref[1], ref[2]);
  return [l1[1] - l2[1], l1[2] - l2[2]];
}

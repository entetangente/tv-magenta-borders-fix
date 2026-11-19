import type { CorrectionMap } from './solve';

/**
 * Binärformat der Korrekturkarte (Little Endian), wird vom Fire TV gelesen:
 *
 *   "EFM1" | u16 gw | u16 gh | u8 Varianten | 3 Byte frei
 *   je Variante: gw·gh × (f32 a, f32 a·O_r, f32 a·O_g, f32 a·O_b), zeilenweise
 *
 * Gleitkommazahlen statt 16-Bit-PNG, weil Android 7.1 16-Bit-PNGs beim
 * Dekodieren auf 8 Bit kürzt.
 */
export function encodeMaps(maps: CorrectionMap[]): ArrayBuffer {
  const { gw, gh } = maps[0];
  const n = gw * gh;
  const buf = new ArrayBuffer(12 + maps.length * n * 16);
  const dv = new DataView(buf);
  dv.setUint8(0, 0x45); dv.setUint8(1, 0x46); dv.setUint8(2, 0x4d); dv.setUint8(3, 0x31);
  dv.setUint16(4, gw, true);
  dv.setUint16(6, gh, true);
  dv.setUint8(8, maps.length);
  let o = 12;
  for (const m of maps) {
    for (let i = 0; i < n; i++) {
      dv.setFloat32(o, m.a[i], true);
      dv.setFloat32(o + 4, m.P[i * 3], true);
      dv.setFloat32(o + 8, m.P[i * 3 + 1], true);
      dv.setFloat32(o + 12, m.P[i * 3 + 2], true);
      o += 16;
    }
  }
  return buf;
}

export function decodeMaps(buf: ArrayBuffer): CorrectionMap[] {
  const dv = new DataView(buf);
  const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
  if (magic !== 'EFM1') throw new Error('kein EdgeFix-Kartenformat');
  const gw = dv.getUint16(4, true);
  const gh = dv.getUint16(6, true);
  const count = dv.getUint8(8);
  const n = gw * gh;
  const out: CorrectionMap[] = [];
  let o = 12;
  for (let v = 0; v < count; v++) {
    const a = new Float32Array(n);
    const P = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      a[i] = dv.getFloat32(o, true);
      P[i * 3] = dv.getFloat32(o + 4, true);
      P[i * 3 + 1] = dv.getFloat32(o + 8, true);
      P[i * 3 + 2] = dv.getFloat32(o + 12, true);
      o += 16;
    }
    out.push({ gw, gh, a, P });
  }
  return out;
}

import { it } from 'vitest';
import { writeFileSync } from 'node:fs';
import { encodeMaps } from '../src/analysis/mapfile';

/** Kleine Beispielkarte für den Formattest der Android-App (CorrectionMapTest). */
it('schreibt Beispielkarte für Android-Test', () => {
  const gw = 8, gh = 5, n = gw * gh;
  const maps = [0, 1].map((v) => {
    const a = new Float32Array(n);
    const P = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const x = i % gw;
      a[i] = x === 0 ? 0.1 + 0.1 * v : 0; // nur linke Spalte aktiv
      P[i * 3] = a[i] * 0.2;
      P[i * 3 + 1] = a[i] * 0.8;
      P[i * 3 + 2] = a[i] * 0.1;
    }
    return { gw, gh, a, P };
  });
  writeFileSync(new URL('../../app/src/test/resources/sample.efm', import.meta.url), new Uint8Array(encodeMaps(maps)));
});

/// <reference types="node" />
/**
 * Ende-zu-Ende-Test der Web-App im echten Browser (Chromium über Playwright).
 * Ein nachgebauter Fire TV liefert die gebaute Web-App aus, spricht dasselbe
 * WebSocket-Protokoll wie CalibrationServer.kt und „fotografiert“ das, was er
 * gerade anzeigt (synthetisches Panel mit lila Rand, inklusive Overlay).
 *
 * Voraussetzung: `npm run build` und ein installiertes Playwright. Fehlt
 * Playwright (z. B. in CI), wird der Test übersprungen.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type Server } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { extname, join } from 'node:path';
import { WebSocketServer, type WebSocket } from 'ws';
import { decodeMaps } from '../src/analysis/mapfile';
import type { CorrectionMap } from '../src/analysis/solve';
import { synthPhoto } from './synth';
import { encodePng } from './png';

const require = createRequire(import.meta.url);
let playwright: any = null;
try {
  playwright = require('playwright');
} catch {
  playwright = null;
}
const WEB = new URL('../../app/src/main/assets/web/', import.meta.url).pathname;
const TOKEN = 'testtoken0123456789abcdef01234567';
const run = playwright && existsSync(join(WEB, 'index.html')) ? describe : describe.skip;

interface TvMock {
  level: number | null;
  overlay: string;
  preview: CorrectionMap[] | null;
  strength: number;
  variant: number;
  compare: boolean;
  applied: boolean;
  log: string[];
}

run('Kalibrierung im Browser', () => {
  let server: Server;
  let port = 0;
  const tv: TvMock = { level: null, overlay: 'active', preview: null, strength: 1, variant: 1, compare: false, applied: false, log: [] };
  const sockets = new Set<WebSocket>();
  const state = () => JSON.stringify({ t: 'state', device: 'Fire TV (Test)', hasPreview: !!tv.preview, strength: tv.strength, variant: tv.variant });

  beforeAll(async () => {
    server = createServer((req, res) => {
      const url = new URL(req.url!, 'http://x');
      const cookieOk = (req.headers.cookie ?? '').includes(`ef=${TOKEN}`);
      if (url.searchParams.get('t') !== TOKEN && !cookieOk) {
        res.writeHead(403).end('forbidden');
        return;
      }
      if (url.pathname === '/api/map' && req.method === 'POST') {
        const parts: Buffer[] = [];
        req.on('data', (d) => parts.push(d));
        req.on('end', () => {
          const buf = Buffer.concat(parts);
          tv.preview = decodeMaps(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length));
          tv.overlay = 'preview';
          tv.log.push(`map ${buf.length}`);
          sockets.forEach((s) => s.send(state()));
          res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}');
        });
        return;
      }
      const path = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      const file = join(WEB, path);
      if (!existsSync(file)) {
        res.writeHead(404).end();
        return;
      }
      const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(file)] ?? 'application/octet-stream';
      const headers: Record<string, string> = { 'Content-Type': type };
      if (path === 'index.html') headers['Set-Cookie'] = `ef=${TOKEN}; Path=/; SameSite=Strict; HttpOnly`;
      res.writeHead(200, headers).end(readFileSync(file));
    });
    const wss = new WebSocketServer({ noServer: true });
    server.on('upgrade', (req, socket, head) => {
      const url = new URL(req.url!, 'http://x');
      if (url.pathname !== '/ws' || url.searchParams.get('t') !== TOKEN) {
        socket.destroy();
        return;
      }
      wss.handleUpgrade(req, socket, head, (ws) => {
        sockets.add(ws);
        ws.send(state());
        ws.on('message', (raw) => {
          const m = JSON.parse(String(raw));
          tv.log.push(m.t + (m.t === 'show' ? ` ${m.level} ${m.overlay}` : ''));
          if (m.t === 'show') { tv.level = m.level; tv.overlay = m.overlay; }
          if (m.t === 'tune') { tv.strength = m.strength; tv.variant = m.variant; }
          if (m.t === 'compare') tv.compare = m.on;
          if (m.t === 'apply') tv.applied = true;
          ws.send(state());
        });
        ws.on('close', () => sockets.delete(ws));
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
    port = (server.address() as any).port;
  });

  afterAll(() => {
    sockets.forEach((s) => s.close());
    server?.close();
  });

  /** „Foto“ von dem, was der nachgebaute Fernseher gerade zeigt. */
  function photo(seed: number): Buffer {
    const map = tv.overlay === 'preview' && tv.preview && !tv.compare ? tv.preview[tv.variant] : undefined;
    const img = synthPhoto({ level: tv.level ?? 0, map, strength: tv.strength, seed, w: 1200, h: 900,
      corners: [{ x: 150, y: 170 }, { x: 1050, y: 160 }, { x: 1065, y: 680 }, { x: 140, y: 690 }] });
    return encodePng(img);
  }

  it('führt durch Aufnahme, Berechnung, Nachmessen und Übernehmen', async () => {
    const browser = await playwright.chromium.launch();
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
    const errors: string[] = [];
    page.on('pageerror', (e: Error) => errors.push(e.message));
    let seed = 1;
    page.on('filechooser', async (fc: any) => {
      await fc.setFiles({ name: 'foto.png', mimeType: 'image/png', buffer: photo(seed++) });
    });
    const shot = async (name: string) =>
      page.screenshot({ path: new URL(`../test-output/${name}.png`, import.meta.url).pathname });

    await page.goto(`http://127.0.0.1:${port}/?t=${TOKEN}`);
    await page.getByText('✓ Verbunden').waitFor();
    await shot('1-verbunden');
    await page.getByRole('button', { name: 'Weiter' }).click();
    for (const cb of await page.locator('input[type=checkbox]').all()) await cb.check();
    await shot('2-vorbereitung');
    await page.getByRole('button', { name: 'Los geht’s' }).click();

    for (let i = 1; i <= 5; i++) {
      await page.getByRole('heading', { name: `Foto ${i} von 5` }).waitFor();
      if (i === 1) await shot('3-aufnahme');
      await page.getByRole('button', { name: 'Foto aufnehmen' }).click();
    }
    await page.getByRole('heading', { name: 'Ergebnis' }).waitFor({ timeout: 60000 });
    await shot('4-ergebnis');
    expect(tv.preview).not.toBeNull();
    expect(tv.overlay).toBe('preview');
    expect(tv.level).toBe(1);

    // Vorher/Nachher und Regler kommen am Fernseher an
    await page.getByRole('button', { name: 'Vorher' }).click();
    await expect.poll(() => tv.compare).toBe(true);
    await page.getByRole('button', { name: 'Nachher' }).click();
    await expect.poll(() => tv.compare).toBe(false);
    await page.getByRole('button', { name: 'Hoch' }).click();
    await expect.poll(() => tv.variant).toBe(2);
    await page.getByRole('button', { name: 'Mittel' }).click();

    // Nachmessen mit eingeschalteter Korrektur
    await page.getByRole('button', { name: /Genauer machen/ }).click();
    for (let i = 1; i <= 2; i++) {
      await page.getByRole('heading', { name: `Nachmessen: Foto ${i} von 2` }).waitFor();
      expect(tv.overlay).toBe('preview');
      await page.getByRole('button', { name: 'Foto aufnehmen' }).click();
    }
    await page.getByRole('heading', { name: 'Ergebnis' }).waitFor({ timeout: 60000 });
    await page.getByText(/Nachgemessen|Nebenwirkungen/).waitFor();
    await shot('5-nachgemessen');

    await page.getByRole('button', { name: 'Übernehmen' }).click();
    await page.getByRole('heading', { name: 'Fertig ✓' }).waitFor();
    await shot('6-fertig');
    expect(tv.applied).toBe(true);
    expect(tv.level).toBeNull();
    expect(errors).toEqual([]);
    await browser.close();
  }, 180000);

  it('zeigt einen konkreten Hinweis bei unbrauchbarem Foto', async () => {
    const browser = await playwright.chromium.launch();
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    page.on('filechooser', async (fc: any) => {
      const img = synthPhoto({ level: 0.75, w: 1200, h: 900, corners: [{ x: -60, y: 170 }, { x: 1050, y: 160 }, { x: 1065, y: 680 }, { x: -60, y: 690 }] });
      await fc.setFiles({ name: 'foto.png', mimeType: 'image/png', buffer: encodePng(img) });
    });
    await page.goto(`http://127.0.0.1:${port}/?t=${TOKEN}`);
    await page.getByRole('button', { name: 'Weiter' }).click();
    for (const cb of await page.locator('input[type=checkbox]').all()) await cb.check();
    await page.getByRole('button', { name: 'Los geht’s' }).click();
    await page.getByRole('button', { name: 'Foto aufnehmen' }).click();
    await page.getByText('Bildschirm nicht vollständig im Bild').waitFor();
    await page.getByRole('button', { name: 'Nochmal versuchen' }).waitFor();
    await browser.close();
  }, 60000);

  it('lehnt Aufrufe ohne Token ab', async () => {
    const res = await fetch(`http://127.0.0.1:${port}/`);
    expect(res.status).toBe(403);
  });
});

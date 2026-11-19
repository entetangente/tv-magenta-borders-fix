/** Verbindung zum Fire TV: WebSocket für Befehle und Zustand, HTTP für die Karte. */

export type Overlay = 'off' | 'preview' | 'active';

export interface TvState {
  device: string;
  hasPreview: boolean;
  strength: number;
  variant: number;
}

export type ToTv =
  | { t: 'show'; level: number | null; hint?: string; overlay: Overlay }
  | { t: 'tune'; strength: number; variant: number }
  | { t: 'compare'; on: boolean }
  | { t: 'apply' }
  | { t: 'step'; text: string };

const token = new URLSearchParams(location.search).get('t') ?? '';

export class TvLink {
  private ws: WebSocket | null = null;
  private queue: string[] = [];
  state: TvState | null = null;
  onState: (s: TvState) => void = () => {};
  onConnection: (connected: boolean) => void = () => {};
  private retry = 0;

  connect(): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws?t=${encodeURIComponent(token)}`);
    this.ws = ws;
    ws.onopen = () => {
      this.retry = 0;
      this.onConnection(true);
      for (const m of this.queue.splice(0)) ws.send(m);
    };
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.t === 'state') {
          this.state = msg;
          this.onState(msg);
        }
      } catch {
        /* ignorieren */
      }
    };
    ws.onclose = () => {
      this.ws = null;
      this.onConnection(false);
      const delay = Math.min(5000, 500 * 2 ** this.retry++);
      setTimeout(() => this.connect(), delay);
    };
  }

  send(msg: ToTv): void {
    const s = JSON.stringify(msg);
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(s);
    else this.queue.push(s);
  }

  async uploadMap(buf: ArrayBuffer, meta: object): Promise<void> {
    const res = await fetch(`/api/map?t=${encodeURIComponent(token)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream', 'X-EdgeFix-Meta': encodeURIComponent(JSON.stringify(meta)) },
      body: buf,
    });
    if (!res.ok) throw new Error(`Fernseher hat die Karte abgelehnt (${res.status})`);
  }
}

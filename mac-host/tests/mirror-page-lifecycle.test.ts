import { expect, test } from 'bun:test';
import vm from 'node:vm';
import { handleDashboardHttpRequest } from '../src/inbox/inbox-web-dashboard.js';

async function page(hud = true) {
  const url = new URL('http://127.0.0.1:23671/mirror' + (hud ? '?hud=1' : ''));
  const response = await handleDashboardHttpRequest({} as any, new Request(url), url);
  const html = await response!.text();
  const script = html.slice(html.lastIndexOf('<script>') + 8, html.lastIndexOf('</script>'));
  let now = 100, id = 0, draws = 0, clears = 0, fetches = 0;
  const timers = new Map<number, { fn: () => void; delay: number }>();
  const events = new Map<string, Function>();
  const nodes = new Map<string, any>();
  const get = (name: string) => {
    if (!nodes.has(name)) nodes.set(name, {
      style: {}, textContent: '', value: name === 'presetSelect' ? '640,72,8' : '', width: 640, height: 1422,
      options: [{ value: '640,72,8' }], disabled: false, hidden: false,
      classList: { add() {}, remove() {} },
      getContext: () => ({ clearRect: () => clears++, drawImage: () => draws++ }),
      addEventListener: (event: string, fn: Function) => events.set(name + ':' + event, fn),
      getBoundingClientRect: () => ({ left: 0, top: 0, width: 320, height: 711 }),
      focus() { document.activeElement = this; }, setPointerCapture() {}, add(value: any) { this.options.push(value); }
    });
    return nodes.get(name);
  };
  const document = { getElementById: get, querySelector: get, querySelectorAll: () => [], activeElement: null as any };
  class Socket {
    static OPEN = 1;
    static all: Socket[] = [];
    readyState = 0;
    sent: any[] = [];
    onopen?: Function; onmessage?: Function; onclose?: Function;
    constructor(public url: string) { Socket.all.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    send(s: string) { this.sent.push(JSON.parse(s)); }
    close() { this.readyState = 3; this.onclose?.(); }
    receive(d: any) { this.onmessage?.({ data: d instanceof ArrayBuffer ? d : JSON.stringify(d) }); }
  }
  const context = vm.createContext({
    document, location: url, URLSearchParams, WebSocket: Socket, ArrayBuffer, DataView, Uint8Array, Blob,
    performance: { now: () => now }, console, Option: class { constructor(public text: string, public value: string) {} },
    // Decode completion must cross an event-loop turn, as a real image decoder can.
    createImageBitmap: () => new Promise(resolve => setImmediate(() => resolve({ width: 640, height: 1422, close() {} }))),
    setTimeout: (fn: () => void, delay: number) => { timers.set(++id, { fn, delay }); return id; },
    setInterval: (fn: () => void, delay: number) => { timers.set(++id, { fn, delay }); return id; },
    clearTimeout: (i: number) => timers.delete(i), clearInterval: (i: number) => timers.delete(i),
    requestAnimationFrame: () => ++id, cancelAnimationFrame() {},
    fetch: () => { fetches++; throw new Error('No real network allowed'); },
    addEventListener: (name: string, fn: Function) => events.set('window:' + name, fn),
  });
  context.window = context;
  vm.runInContext(script, context);
  const run = (s: string) => vm.runInContext(s, context);
  const frame = (flags = 0) => {
    const buf = new ArrayBuffer(17), view = new DataView(buf);
    view.setUint8(0, 1); view.setUint8(1, flags); view.setUint16(2, 640); view.setUint16(4, 1422);
    return buf;
  };
  const settleFrames = async () => {
    const deadline = Date.now() + 1000;
    while (run('decoding')) {
      if (Date.now() >= deadline) throw new Error('Synthetic frame decoding did not settle');
      await new Promise<void>(resolve => setImmediate(resolve));
    }
  };
  const start = async () => {
    run('toggleStartStop()');
    const socket = Socket.all.at(-1)!;
    socket.open(); socket.receive({ type: 'mirror_status', status: { running: false } });
    socket.receive({ type: 'mirror_status', status: { running: true } });
    socket.receive(frame()); await settleFrames();
    return socket;
  };
  return { run, get, start, frame, settleFrames, context, timers, events, sockets: Socket.all,
    counts: () => ({ draws, clears, fetches }), advance: (ms: number) => now += ms };
}

test('HUD page stays idle until explicit start; exit cancels reconnect and never globally stops', async () => {
  const p = await page();
  expect(p.sockets).toHaveLength(0);
  expect(p.get('mirrorTextInput').disabled).toBe(true);
  const ws = await p.start();
  expect(ws.url).toContain('?manual=1');
  expect(ws.sent[0]).toMatchObject({ type: 'start', autoWake: false });
  expect(p.counts().draws).toBe(1);
  ws.close();
  expect([...p.timers.values()].some(t => t.delay === 1000)).toBe(true);
  p.run('hostDeactivate()');
  expect(p.timers.size).toBe(0);
  expect(ws.sent.some(s => s.type === 'stop')).toBe(false);
  expect(p.counts().fetches).toBe(0);
});

test('text waits for correlated ACK; rejection retains text; disconnected controls never use HTTP', async () => {
  const p = await page(), ws = await p.start();
  p.get('mirrorTextInput').value = 'synthetic text';
  const pending = p.run('submitMirrorText(false)');
  expect(p.get('mirrorTextInput').value).toBe('synthetic text');
  const reqId = ws.sent.at(-1).reqId;
  ws.receive({ type: 'input_ack', reqId: 'unrelated', ok: true });
  expect(p.get('mirrorTextInput').value).toBe('synthetic text');
  ws.receive({ type: 'input_ack', reqId, ok: true, result: { ok: false, error: 'ERR_EMERGENCY_STOP' } });
  await pending;
  expect(p.get('mirrorTextInput').value).toBe('synthetic text');
  const success = p.run('submitMirrorText(false)');
  ws.receive({ type: 'input_ack', reqId: ws.sent.at(-1).reqId, ok: true, result: { ok: true } });
  await success;
  expect(p.get('mirrorTextInput').value).toBe('');
  p.run('hostDeactivate()');
  expect(await p.run("sendCommand({type:'input',inputType:'tap'})")).toBe(false);
  expect(p.counts().fetches).toBe(0);
});

test('locked/secure frames, stale decoding and composition never cause hidden input or old rendering', async () => {
  const p = await page(), ws = await p.start();
  let resolve!: (b: any) => void;
  p.context.createImageBitmap = () => new Promise(r => resolve = r);
  ws.receive(p.frame());
  const before = p.counts().draws;
  ws.receive(p.frame(4));
  resolve({ width: 640, height: 1422, close() {} });
  await p.settleFrames();
  expect(p.counts().draws).toBe(before);
  expect(p.get('mirrorTextInput').disabled).toBe(true);
  const count = ws.sent.length;
  p.events.get('mirrorCanvas:keydown')!({ key: 'Enter', isComposing: true });
  p.run("onTextInputKey({key:'Enter', isComposing:true})");
  expect(ws.sent.length).toBe(count);
  p.run('hostDeactivate()');
});

test('HUD joining active stream does not restart it; normal browser still starts automatically', async () => {
  const p = await page();
  p.run('toggleStartStop()'); const ws = p.sockets[0]; ws.open();
  ws.receive({ type: 'mirror_status', status: { running: true, maxWidth: 540, quality: 68, maxFps: 8 } });
  expect(ws.sent).toEqual([]);
  expect(p.get('presetSelect').value).toBe('540,68,8');
  ws.receive({ type: 'mirror_status', status: { running: false } });
  expect(ws.sent).toEqual([]);
  p.run('hostDeactivate()');
  const browser = await page(false);
  expect(browser.sockets).toHaveLength(1);
  browser.sockets[0].open();
  expect(browser.sockets[0].sent[0]).toMatchObject({ type: 'start', autoWake: true });
  browser.run('hostDeactivate()');
});

test('pending start is joined without duplicate start; close cancels unacknowledged input timers', async () => {
  const p = await page();
  p.run('toggleStartStop()'); const ws = p.sockets[0]; ws.open();
  ws.receive({ type: 'mirror_status', status: { running: false, starting: true } });
  expect(ws.sent).toEqual([]);
  ws.receive({ type: 'mirror_status', status: { running: true } });
  ws.receive(p.frame()); await p.settleFrames();
  const promise = p.run("sendCommand({type:'input',inputType:'tap'})");
  expect([...p.timers.values()].some(t => t.delay === 68000)).toBe(true);
  p.run('hostDeactivate()');
  expect(await promise).toBe(false);
  expect(p.timers.size).toBe(0);
});

test('bounded reconnect ends without reopening and stale frame gates input', async () => {
  const p = await page(), ws = await p.start();
  p.advance(5100);
  for (const t of [...p.timers.values()]) if (t.delay === 1000) t.fn();
  expect(p.get('mirrorTextInput').disabled).toBe(true);
  ws.close();
  // Fake timers execute only the reconnect callback; no real network or sleep.
  for (let i = 0; i < 5; i++) {
    const entry = [...p.timers.entries()].filter(([,t]) => t.delay >= 1000 && t.delay <= 10000).at(-1)!;
    p.timers.delete(entry[0]); entry[1].fn();
    p.sockets.at(-1)!.close();
  }
  expect(p.run('active')).toBe(false);
  expect(p.sockets).toHaveLength(6);
  expect([...p.timers.values()].filter(t => t.delay !== 1800)).toHaveLength(0);
});

test('a global stop while start is pending is not revived by connection timeout', async () => {
  const p = await page();
  p.run('toggleStartStop()'); const ws = p.sockets[0]; ws.open();
  ws.receive({ type: 'mirror_status', status: { running: false } });
  expect(ws.sent).toHaveLength(1);
  ws.receive({ type: 'mirror_status', status: { running: false, starting: false } });
  expect([...p.timers.values()].some(t => t.delay === 10000)).toBe(false);
  expect(ws.sent).toHaveLength(1);
  p.run('hostDeactivate()');
});

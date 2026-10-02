// R-M12 from INSIDE Obsidian's WebView (origin http://localhost), the way the plugin will call
// the bridge: fetch and CapacitorHttp. Driven over CDP as note-reader-local/AGENTS.md records.
// The token arrives on stdin, never in argv or a URL. Prints one line: OBSIDIAN {json}.
// Usage: printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs <idle|playing>
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const mode = process.argv[2];
if (mode !== 'idle' && mode !== 'playing') throw new Error('usage: obsidian-cdp-bridge.mjs <idle|playing>');
const token = readFileSync(0, 'utf8').trim();
if (!/^[0-9a-f]{32}$/.test(token)) throw new Error('expected a 32-hex token on stdin');
const adb = (...a) => execFileSync('adb', a, {encoding: 'utf8'}).trim();
const pid = adb('shell', 'pidof', 'md.obsidian');
if (!pid) throw new Error('Obsidian is not running');
adb('forward', 'tcp:9333', `localabstract:webview_devtools_remote_${pid}`);
try {
  const pages = await (await fetch('http://127.0.0.1:9333/json', {signal: AbortSignal.timeout(10_000)})).json();
  const page = pages.find(p => p.type === 'page' && p.webSocketDebuggerUrl);
  if (!page) throw new Error('no debuggable Obsidian page');
  const expression = `(async () => {
    const out = {};
    const base = 'http://127.0.0.1:8787';
    const auth = {Authorization: 'Bearer ${token}', 'Content-Type': 'text/plain'};
    const big = 'a'.repeat(65536);
    const tryIt = async (k, f) => { try { out[k] = await f(); } catch (e) { out[k] = 'ERR ' + String(e); } };
    await tryIt('health', async () => {
      const r = await fetch(base + '/health');
      const j = await r.json();
      return r.status + ' busy=' + j.busy;
    });
    if (${JSON.stringify(mode)} === 'idle') {
      await tryIt('capSynth', async () => {
        const r = await Capacitor.Plugins.CapacitorHttp.post({url: base + '/synthesize?rate=1.0',
          headers: auth, data: 'The bridge through CapacitorHttp.', responseType: 'blob'});
        return r.status + ' rate=' + (r.headers['X-Rate'] ?? r.headers['x-rate']);
      });
      await tryIt('fetchSynth', async () => {
        const r = await fetch(base + '/synthesize?rate=1.0', {method: 'POST', headers: auth, body: 'The bridge through fetch.'});
        const b = await r.arrayBuffer();
        return r.status + ' rate=' + r.headers.get('X-Rate') + ' ms=' + r.headers.get('X-Synth-Ms') + ' bytes=' + b.byteLength;
      });
      // ADR 0004 "not yet measured": a 64 KiB unauthenticated POST must read as 401, not a network error.
      await tryIt('bigNoToken', async () => (await fetch(base + '/synthesize', {method: 'POST', body: big})).status);
    } else {
      // ADR 0004: a 64 KiB POST while Read Me plays must read as 503 busy.
      await tryIt('bigBusy', async () => {
        const r = await fetch(base + '/synthesize', {method: 'POST', headers: auth, body: big});
        return r.status + ' ' + (await r.text());
      });
    }
    return JSON.stringify(out);
  })()`;
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let timer;
  const reply = await Promise.race([
    new Promise(resolve => {
      ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) resolve(d); };
      ws.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {expression, awaitPromise: true, returnByValue: true}}));
    }),
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('CDP evaluate timed out after 90 s')), 90_000); }),
  ]).finally(() => clearTimeout(timer));
  ws.close();
  console.log('OBSIDIAN', reply.result?.result?.value ?? JSON.stringify(reply.result?.exceptionDetails ?? reply));
} finally {
  adb('forward', '--remove', 'tcp:9333');
}

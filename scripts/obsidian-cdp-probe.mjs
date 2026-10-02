// SPIKE-01: from INSIDE Obsidian's WebView (origin http://localhost), call the spike bridge
// with fetch and with CapacitorHttp, the two paths the plugin can use. Driven over CDP, the
// same way note-reader-local/AGENTS.md measured the prototype. Dev tooling, not app code.
// The token arrives on stdin, so it never appears in argv or a URL.
// Usage: printf '%s' "$TOK" | node scripts/obsidian-cdp-probe.mjs
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

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
    const auth = {Authorization: 'Bearer ${token}', 'Content-Type': 'text/plain'};
    try { out.fetchHealth = (await fetch('http://127.0.0.1:8787/health')).status; }
    catch (e) { out.fetchHealth = String(e); }
    try {
      const r = await Capacitor.Plugins.CapacitorHttp.post({url: 'http://127.0.0.1:8787/synthesize?rate=1.0',
        headers: auth, data: 'Background bridge spike through CapacitorHttp.', responseType: 'blob'});
      out.capSynth = r.status;
      out.capRate = r.headers['X-Rate'] ?? r.headers['x-rate'] ?? null;
    } catch (e) { out.capSynth = String(e); }
    try {
      const r = await fetch('http://127.0.0.1:8787/synthesize?rate=1.0',
        {method: 'POST', headers: auth, body: 'Background bridge spike through fetch.'});
      out.fetchSynth = r.status; out.fetchSynthMs = r.headers.get('X-Synth-Ms'); out.fetchRate = r.headers.get('X-Rate');
    } catch (e) { out.fetchSynth = String(e); }
    try { out.fetchNoToken = (await fetch('http://127.0.0.1:8787/synthesize', {method: 'POST', body: 'x'})).status; }
    catch (e) { out.fetchNoToken = String(e); }
    return JSON.stringify(out);
  })()`;
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  // Bounded: a hung evaluate must not hold the device slot forever. The timer is cleared so a
  // successful probe exits at once instead of waiting out the 90 s.
  let timer;
  const reply = await Promise.race([
    new Promise(resolve => {
      ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) resolve(d); };
      ws.send(JSON.stringify({id: 1, method: 'Runtime.evaluate',
        params: {expression, awaitPromise: true, returnByValue: true}}));
    }),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('CDP evaluate timed out after 90 s')), 90_000);
    }),
  ]).finally(() => clearTimeout(timer));
  ws.close();
  console.log('OBSIDIAN', reply.result?.result?.value ?? JSON.stringify(reply.result?.exceptionDetails ?? reply));
} finally {
  adb('forward', '--remove', 'tcp:9333');
}

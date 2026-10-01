// Phase 0 spike harness. A probe runs inside the release build, triggered by
// `am start --es spike <name>`, and reports one SPIKE_RESULT line to logcat
// (tag ReactNativeJS). Probes report numbers and flags only, never item text (R-M09.3).
import {segmenterProbe} from './segmenterProbe';

export type Probe = () => Promise<Record<string, unknown>>;

export const PROBES: Record<string, Probe> = {
  ping: async () => ({ok: true, hermes: 'HermesInternal' in globalThis}),
  segmenter: segmenterProbe,
};

function report(spike: string, result: Record<string, unknown>): void {
  console.log(`SPIKE_RESULT ${JSON.stringify({spike, ...result})}`);
}

export async function runSpike(name: string): Promise<string> {
  const probe = PROBES[name];
  if (!probe) {
    report(name, {error: 'unknown spike'});
    return 'unknown spike';
  }
  try {
    report(name, await probe());
    return 'done';
  } catch (e) {
    report(name, {error: e instanceof Error ? e.message : String(e)});
    return 'error';
  }
}

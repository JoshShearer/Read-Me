import {runSpike} from '../src/spikes/run';
import {segmenterProbe} from '../src/spikes/segmenterProbe';

function captureLogs(): {lines: string[]; restore: () => void} {
  const lines: string[] = [];
  const spy = jest.spyOn(console, 'log').mockImplementation((...a: unknown[]) => {
    lines.push(a.map(String).join(' '));
  });
  return {lines, restore: () => spy.mockRestore()};
}

function payload(line: string): Record<string, unknown> {
  expect(line.startsWith('SPIKE_RESULT ')).toBe(true);
  return JSON.parse(line.slice('SPIKE_RESULT '.length));
}

test('ping logs exactly one SPIKE_RESULT line', async () => {
  const log = captureLogs();
  const status = await runSpike('ping');
  log.restore();
  expect(status).toBe('done');
  expect(log.lines).toHaveLength(1);
  expect(payload(log.lines[0])).toMatchObject({spike: 'ping', ok: true});
});

test('unknown spike reports an error instead of throwing', async () => {
  const log = captureLogs();
  const status = await runSpike('nope');
  log.restore();
  expect(status).toBe('unknown spike');
  expect(payload(log.lines[0])).toMatchObject({spike: 'nope', error: 'unknown spike'});
});

test('segmenterProbe reports sentences with offsets when Intl.Segmenter exists (Node has it)', async () => {
  const r = await segmenterProbe();
  expect(r.present).toBe(true);
  const sentences = r.sentences as {index: number; text: string}[];
  expect(sentences.length).toBeGreaterThanOrEqual(4);
  expect(sentences[0].index).toBe(0);
  expect(typeof r.bigMs).toBe('number');
});

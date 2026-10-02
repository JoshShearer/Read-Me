import { GUARDED_NAMES, installRuntimeGuard } from '../src/net/guard';

test('each JS network API throws once the guard is installed, and stays replaced', () => {
  const target: Record<string, unknown> = {
    [GUARDED_NAMES[0]]: () => 'real',
    [GUARDED_NAMES[1]]: class {},
    [GUARDED_NAMES[2]]: class {},
  };
  installRuntimeGuard(target);
  for (const name of GUARDED_NAMES) {
    const api = target[name] as (...a: unknown[]) => unknown;
    expect(() => api('https://example.com')).toThrow('network access is disabled');
    expect(() => new (api as unknown as new () => unknown)()).toThrow(
      'network access is disabled',
    );
    // React Native's Babel preset compiles without strict mode, so the write is silently
    // ignored rather than a TypeError; either way the stub must stay.
    try {
      target[name] = () => 'again';
    } catch {
      // strict mode: TypeError, also fine
    }
    expect(target[name]).toBe(api);
  }
});

// R-M09.2 as amended (F16): the source guard cannot see bundled third-party JS, so release
// builds replace the JS network APIs with stubs that throw, before any app module loads.
// This is the one file allowed to name them (see __tests__/networkGuard.test.ts).
export const GUARDED_NAMES = ['fetch', 'XMLHttpRequest', 'WebSocket'] as const;

const MESSAGE = 'network access is disabled in Read Me (R-M09)';

function blocked(): never {
  throw new Error(MESSAGE);
}

export function installRuntimeGuard(target: Record<string, unknown>): void {
  for (const name of GUARDED_NAMES) {
    Object.defineProperty(target, name, {
      value: blocked,
      writable: false,
      configurable: false,
      enumerable: false,
    });
  }
}

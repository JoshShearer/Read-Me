// Imported first by index.js, so the stubs are in place before App and its imports load.
// Debug builds keep the APIs: Metro's reload and the dev tools use them.
import { installRuntimeGuard } from './guard';

if (!__DEV__) installRuntimeGuard(globalThis as unknown as Record<string, unknown>);

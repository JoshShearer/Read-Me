const mockEmitter = { addListener: jest.fn(() => ({ remove: jest.fn() })) };
const mockPlatform = { OS: 'android', Version: 36 };
const mockPermissions = {
  PERMISSIONS: { POST_NOTIFICATIONS: 'android.permission.POST_NOTIFICATIONS' },
  RESULTS: { GRANTED: 'granted', DENIED: 'denied' },
  check: jest.fn(async () => false),
  request: jest.fn(async () => 'granted'),
};
// Getters: the factory runs at import time, before these consts are initialized.
jest.mock('react-native', () => ({
  NativeEventEmitter: jest.fn(() => mockEmitter),
  get Platform() {
    return mockPlatform;
  },
  get PermissionsAndroid() {
    return mockPermissions;
  },
}));
jest.mock('../src/native/NativeReadMeSpeech', () => ({ __esModule: true, default: {} }));

import { ensureNotifications, onBridge } from '../src/library/bridge';

beforeEach(() => {
  mockPlatform.Version = 36;
  mockPermissions.check.mockClear().mockResolvedValue(false);
  mockPermissions.request.mockClear().mockResolvedValue('granted');
});

test('onBridge listens for ReadMeBridge and unsubscribes', () => {
  const cb = jest.fn();
  const off = onBridge(cb);
  expect(mockEmitter.addListener).toHaveBeenCalledWith('ReadMeBridge', expect.any(Function));
  const handler = (mockEmitter.addListener.mock.calls[0] as unknown[])[1] as (b: unknown) => void;
  handler({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  expect(cb).toHaveBeenCalledWith({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  off();
});

// R-M12: the bridge's notification must be visible; Android 13+ hides a non-media one
// without POST_NOTIFICATIONS (seen on the reference device, 2026-10-02, build c460f84).
test('ensureNotifications asks on Android 13+ when not yet granted', async () => {
  expect(await ensureNotifications()).toBe(true);
  expect(mockPermissions.request).toHaveBeenCalledWith('android.permission.POST_NOTIFICATIONS');
});

test('ensureNotifications reports a refusal', async () => {
  mockPermissions.request.mockResolvedValue('denied');
  expect(await ensureNotifications()).toBe(false);
});

test('ensureNotifications does not ask when granted or before Android 13', async () => {
  mockPermissions.check.mockResolvedValue(true);
  expect(await ensureNotifications()).toBe(true);
  mockPermissions.check.mockResolvedValue(false);
  mockPlatform.Version = 32;
  expect(await ensureNotifications()).toBe(true);
  expect(mockPermissions.request).not.toHaveBeenCalled();
});

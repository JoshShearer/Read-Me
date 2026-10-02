const mockEmitter = { addListener: jest.fn(() => ({ remove: jest.fn() })) };
jest.mock('react-native', () => ({ NativeEventEmitter: jest.fn(() => mockEmitter) }));
jest.mock('../src/native/NativeReadMeSpeech', () => ({ __esModule: true, default: {} }));

import { onBridge } from '../src/library/bridge';

test('onBridge listens for ReadMeBridge and unsubscribes', () => {
  const cb = jest.fn();
  const off = onBridge(cb);
  expect(mockEmitter.addListener).toHaveBeenCalledWith('ReadMeBridge', expect.any(Function));
  const handler = (mockEmitter.addListener.mock.calls[0] as unknown[])[1] as (b: unknown) => void;
  handler({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  expect(cb).toHaveBeenCalledWith({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  off();
});

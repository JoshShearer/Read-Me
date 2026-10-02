// R-M12 and R-M01 Settings: the bridge's settings, through ReadMeSpeech. The bridge itself is
// Kotlin (BridgeServer); JS never serves or calls it.
import { NativeEventEmitter } from 'react-native';
import Native, { type NativeBridge } from '../native/NativeReadMeSpeech';

export type Bridge = NativeBridge;

export const getBridge = (): Promise<Bridge> => Native.getBridge();
export const setBridgeEnabled = (on: boolean): Promise<Bridge> => Native.setBridgeEnabled(on);
export const setBridgePort = (port: number): Promise<Bridge> => Native.setBridgePort(port);
export const regenerateBridgeToken = (): Promise<Bridge> => Native.regenerateBridgeToken();
export const copyBridgeToken = (): Promise<void> => Native.copyBridgeToken();

export function onBridge(cb: (b: Bridge) => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener('ReadMeBridge', (n: unknown) => cb(n as Bridge));
  return () => sub.remove();
}

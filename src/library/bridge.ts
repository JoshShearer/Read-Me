// R-M12 and R-M01 Settings: the bridge's settings, through ReadMeSpeech. The bridge itself is
// Kotlin (BridgeServer); JS never serves or calls it.
import { NativeEventEmitter, PermissionsAndroid, Platform } from 'react-native';
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

/**
 * R-M12: "its foreground notification MUST say so". Android 13+ hides a foreground-service
 * notification that is not a media one unless POST_NOTIFICATIONS is granted, so turning the
 * bridge on asks for it, and the bridge stays off while it is refused.
 */
const needsPermission = () => Platform.OS === 'android' && Number(Platform.Version) >= 33;

/** Whether the bridge's notification can show, without asking. */
export async function notificationsAllowed(): Promise<boolean> {
  if (!needsPermission()) return true;
  return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
}

export async function ensureNotifications(): Promise<boolean> {
  if (await notificationsAllowed()) return true;
  const r = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  return r === PermissionsAndroid.RESULTS.GRANTED;
}

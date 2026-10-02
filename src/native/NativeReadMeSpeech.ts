// The ReadMeSpeech TurboModule (ADR 0001): JS reads and writes the library only through it.
// Codegen reads this file (package.json codegenConfig). Ids are numbers on this side.
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativeItem = {
  id: number;
  kind: string;
  url: string | null;
  title: string;
  site: string | null;
  byline: string | null;
  createdAt: number;
  state: string;
  failReason: string | null;
  openedAt: number | null;
  archivedAt: number | null;
};

export type NativeParagraph = { kind: string; text: string };

export type NativeItemDetail = {
  item: NativeItem;
  paragraphs: NativeParagraph[];
  cuts: number[];
};

export interface Spec extends TurboModule {
  listItems(): Promise<NativeItem[]>;
  getItem(id: number): Promise<NativeItemDetail | null>;
  getBody(id: number): Promise<string | null>;
  completeExtraction(
    id: number,
    title: string,
    site: string | null,
    byline: string | null,
    paragraphs: NativeParagraph[],
    poor: boolean,
  ): Promise<boolean>;
  retryFetch(id: number): Promise<boolean>;
  deleteItem(id: number): Promise<void>;
  markOpened(id: number): Promise<void>;
  setCut(id: number, paragraphIndex: number, cut: boolean): Promise<void>;
  archiveItem(id: number): Promise<void>;
  restoreItem(id: number): Promise<void>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('ReadMeSpeech');

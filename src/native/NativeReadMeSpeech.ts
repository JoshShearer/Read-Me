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
  // R-M01: over kept paragraphs; progress is read characters / kept characters, 0..1.
  words: number;
  progress: number;
};

export type NativeParagraph = { kind: string; text: string };

export type NativeItemDetail = {
  item: NativeItem;
  paragraphs: NativeParagraph[];
  cuts: number[];
};

// One utterance for PlaybackService (R-M07): text is paragraph.text.slice(start, end).
export type NativeSentence = {
  paragraphIndex: number;
  start: number;
  end: number;
  text: string;
};

export type NativePosition = { paragraphIndex: number; charOffset: number };

// The service's state. paragraphIndex is -1 when there is no current sentence. engine is
// 'unknown' until a service has started, then 'pending', 'ready', 'no-engine' or 'no-voice'.
export type NativePlayback = {
  itemId: number | null;
  playing: boolean;
  paragraphIndex: number;
  start: number;
  end: number;
  rate: number;
  engine: string;
};

export type NativeVoice = { name: string; language: string; quality: number };

// R-M06: status 'ready', 'no-engine' or 'no-voice'; voices are offline and installed only.
export type NativeEngine = {
  status: string;
  voices: NativeVoice[];
  selected: string | null;
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
  play(
    itemId: number,
    title: string,
    sentences: NativeSentence[],
    startIndex: number,
  ): Promise<boolean>;
  pause(): Promise<boolean>;
  resume(): Promise<boolean>;
  next(): Promise<boolean>;
  previous(): Promise<boolean>;
  backParagraph(): Promise<boolean>;
  setRate(rate: number): Promise<number>;
  getRate(): Promise<number>;
  getPlayback(): Promise<NativePlayback>;
  getPosition(itemId: number): Promise<NativePosition | null>;
  setCuts(id: number, indices: number[]): Promise<void>;
  stop(): Promise<boolean>;
  getEngine(): Promise<NativeEngine>;
  setVoice(name: string | null): Promise<void>;
  getNotices(): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('ReadMeSpeech');

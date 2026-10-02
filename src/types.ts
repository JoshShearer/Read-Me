// Shared shapes for the text pipeline. A paragraph's index in an item's array is its
// paragraph index; positions address text by (paragraphIndex, charOffset), never by a
// sentence index, because segmentation can change between runtime versions (R-M08, R-M11).
export type ParagraphKind = 'p' | 'heading' | 'li';

export type Paragraph = { kind: ParagraphKind; text: string };

/** `text` is exactly `paragraphs[paragraphIndex].text.slice(start, end)`. */
export type Sentence = {
  paragraphIndex: number;
  start: number;
  end: number;
  text: string;
};

export type Position = { paragraphIndex: number; charOffset: number };

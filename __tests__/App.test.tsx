/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => []),
    getBody: jest.fn(async () => null),
    completeExtraction: jest.fn(async () => true),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
    getEngine: jest.fn(async () => ({ status: 'ready', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] })),
    getPlayback: jest.fn(async () => ({
      itemId: null, playing: false, paragraphIndex: -1, start: 0, end: 0, rate: 2, engine: 'unknown',
    })),
  },
}));
import App from '../App';

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});

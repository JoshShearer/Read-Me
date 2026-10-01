module.exports = {
  preset: '@react-native/jest-preset',
  // linkedom's CJS build requires ESM-only packages (css-select and friends); transform them.
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|linkedom|css-select|css-what|htmlparser2|domhandler|domutils|dom-serializer|domelementtype|entities|nth-check|boolbase)/)',
  ],
};

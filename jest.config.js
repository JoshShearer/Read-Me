module.exports = {
  preset: '@react-native/jest-preset',
  // external/: git submodules (Hermes, REA-40) carry their own tests.
  roots: ['<rootDir>'],
  testPathIgnorePatterns: ['/node_modules/', '<rootDir>/external/'],
  modulePathIgnorePatterns: ['<rootDir>/external/'],
  // linkedom's CJS build requires ESM-only packages (css-select and friends); transform them
  // (SPIKE-02).
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|linkedom|css-select|css-what|htmlparser2|domhandler|domutils|dom-serializer|domelementtype|entities|nth-check|boolbase)/)',
  ],
};

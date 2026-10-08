module.exports = {
  root: true,
  extends: '@react-native',
  // external/: git submodules (Hermes, REA-40), not our code.
  ignorePatterns: ['android/**/build/**', '**/*.generated.ts', 'external/**'],
};

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // htmlparser2 (via linkedom) ships `export * as ns`; without this the release bundle fails
  // (SPIKE-02).
  plugins: ['@babel/plugin-transform-export-namespace-from'],
};

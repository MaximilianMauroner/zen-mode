// Test builds offer diagnostic recordings. Store builds do not.
module.exports = ({ config }) => ({
  ...config,
  extra: { ...config.extra, testBuild: process.env.ZEN_MODE_TEST_BUILD === '1' },
});

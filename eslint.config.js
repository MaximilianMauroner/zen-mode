// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require("eslint-config-expo/flat");

const ORDERING_TIMERS = '/^(setTimeout|requestAnimationFrame|requestIdleCallback|queueMicrotask)$/';
const ORDERING_TIMER_MESSAGE =
  'Do not use a timer, frame, or microtask to order work. Wait for the mounted node, the committed state, or the result of the operation.';

module.exports = defineConfig([
  expoConfig,
  {
    ignores: ["dist/*"],
  },
  {
    rules: {
      'no-restricted-syntax': ['error',
        { selector: `CallExpression[callee.name=${ORDERING_TIMERS}]`, message: ORDERING_TIMER_MESSAGE },
        { selector: `CallExpression[callee.property.name=${ORDERING_TIMERS}]`, message: ORDERING_TIMER_MESSAGE },
      ],
    },
  },
  {
    // These files wait for wall-clock time. No other work depends on their order.
    files: ['src/app/report.tsx', 'scripts/**'],
    rules: { 'no-restricted-syntax': 'off' },
  },
]);

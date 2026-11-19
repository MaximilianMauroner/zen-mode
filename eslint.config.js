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
    files: ['**/*.{ts,tsx}'],
    // The diagnostic poll waits for wall-clock time.
    ignores: ['src/app/report.tsx'],
    rules: {
      'no-restricted-syntax': ['error',
        { selector: `CallExpression[callee.name=${ORDERING_TIMERS}]`, message: ORDERING_TIMER_MESSAGE },
        { selector: `CallExpression[callee.computed=false][callee.property.name=${ORDERING_TIMERS}]`, message: ORDERING_TIMER_MESSAGE },
        { selector: `CallExpression[callee.computed=true][callee.property.value=${ORDERING_TIMERS}]`, message: ORDERING_TIMER_MESSAGE },
      ],
    },
  },
]);

/** @type {import('tailwindcss').Config} */
// Colors live in src/theme/palette.json so Tailwind classes and runtime props
// (icon colors, refresh tints) never drift apart.
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: require('./src/theme/palette.json'),
      fontFamily: {
        sans: ['System'],
      },
    },
  },
  plugins: [],
};

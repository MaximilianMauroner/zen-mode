/** @type {import('tailwindcss').Config} */
// Colors live in src/theme/palette.json so Tailwind classes and runtime props
// (icon colors, refresh tints) never drift apart. Type roles and their use are
// documented in docs/DESIGN.md.
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: require('./src/theme/palette.json'),
      fontFamily: {
        sans: ['System'],
      },
      fontSize: {
        metric: ['40px', '46px'],
        display: ['34px', '40px'],
        headline: ['20px', '26px'],
        title: ['17px', '22px'],
        item: ['15px', '20px'],
        body: ['14px', '21px'],
        support: ['13px', '19px'],
        caption: ['12px', '17px'],
        micro: ['11px', '14px'],
      },
    },
  },
  plugins: [],
};

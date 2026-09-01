/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        ink: '#152019',
        moss: '#436753',
        sage: '#8FA997',
        mist: '#E8EFE9',
        cream: '#F6F4EC',
        paper: '#FCFBF7',
        clay: '#D88568',
      },
      fontFamily: {
        sans: ['System'],
      },
    },
  },
  plugins: [],
};

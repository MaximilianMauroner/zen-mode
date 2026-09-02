/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        night: '#0D1210',
        panel: '#141B17',
        panel2: '#1B2720',
        line: '#2B3A30',
        copy: '#EDF5EF',
        muted: '#94A89A',
        accent: '#A7E782',
        onAccent: '#0B1609',
        danger: '#FF9A79',
        dangerBg: '#211511',
        dangerLine: '#5D3428',
        track: '#27372C',
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

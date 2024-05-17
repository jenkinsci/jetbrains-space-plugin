/** @type {import('tailwindcss').Config} */
module.exports = {
  corePlugins: {
    preflight: false, // so that standard styles for html tags like p, ul, li aren't overwritten
  },
  darkMode: 'class',
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
  ],
  theme: {
    fontFamily: {
      sans: [
        "Inter, sans-serif",
      ],
    },
    colors: {
      transparent: 'transparent',
      'text-color': '#0c0c0d',
      'secondary-text-color': '#8f9499',
      'white': '#ffffff',
      'background-dark': '#1d2125',
      'main-color': '#167dff',
      'accent-color': '#0a64d6',
      'error-color': '#EF341E'
    },
    extend: {},
  },
  plugins: [],
}


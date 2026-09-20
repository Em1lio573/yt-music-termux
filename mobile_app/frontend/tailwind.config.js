/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        ios: {
          bg: '#000000',
          card: 'rgba(28, 28, 30, 0.75)',
          border: 'rgba(255, 255, 255, 0.12)',
          tint: '#FA2D48', // Apple Music Red/Pink
          blue: '#0A84FF',
          green: '#30D158',
          yellow: '#FFD60A',
          purple: '#BF5AF2',
          subtext: '#8E8E93',
          separator: 'rgba(84, 84, 88, 0.4)'
        }
      },
      fontFamily: {
        sf: ['-apple-system', 'BlinkMacSystemFont', '"SF Pro Text"', '"SF Pro Display"', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        'glass': '0 8px 32px 0 rgba(0, 0, 0, 0.37)',
        'float': '0 12px 40px rgba(0, 0, 0, 0.45)',
      }
    },
  },
  plugins: [],
}

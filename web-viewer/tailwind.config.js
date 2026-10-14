/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        perch: {
          navy: '#0F1923',
          card: '#1C2A3A',
          blue: '#2979FF',
          dark: '#141F2B',
          border: '#1A2634',
        }
      }
    },
  },
  plugins: [],
}

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{vue,js,ts,jsx,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      fontFamily: {
        sans: ["Archivo", "system-ui", "-apple-system", "sans-serif"],
        display: ["Archivo", "system-ui", "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      colors: {
        // Paleta da marca HRB — preto e amarelo
        brand: {
          yellow: "#FFD600",
          "yellow-dark": "#E6C000",
          "yellow-deep": "#C7A600",
          "yellow-light": "#FFE84D",
          "yellow-soft": "#FFF7CC",
          black: "#111111",
          "black-soft": "#1C1C1A",
          ink: "#16160F",
        },
        // Namespace legado `meli.*` remapeado para as cores da marca,
        // assim usos existentes de bg-meli-blue / text-meli-blue
        // viram automaticamente preto da marca.
        meli: {
          yellow: "#FFD600",
          blue: "#111111",
          "blue-dark": "#000000",
          "gray-light": "#EEEEEE",
        },
        // Neutros levemente aquecidos — harmonizam com o amarelo da marca.
        // Substituem o `gray` padrão em TODO o app (texto, bordas, fundos)
        // sem precisar editar página por página.
        gray: {
          50: "#FAFAF6",
          100: "#F3F2EC",
          200: "#E7E5DC",
          300: "#D5D3C8",
          400: "#A7A599",
          500: "#75736A",
          600: "#57554D",
          700: "#403E37",
          800: "#2A2924",
          900: "#1A1915",
          950: "#121110",
        },
      },
      boxShadow: {
        // Sombras suaves e quentes — menos "cinza azulado", mais tinta
        sm: "0 1px 2px 0 rgba(26, 25, 21, 0.05)",
        DEFAULT:
          "0 1px 2px 0 rgba(26, 25, 21, 0.05), 0 2px 8px -2px rgba(26, 25, 21, 0.06)",
        md: "0 2px 4px -1px rgba(26, 25, 21, 0.05), 0 6px 16px -4px rgba(26, 25, 21, 0.09)",
        lg: "0 4px 8px -2px rgba(26, 25, 21, 0.06), 0 14px 32px -8px rgba(26, 25, 21, 0.14)",
        xl: "0 8px 16px -4px rgba(26, 25, 21, 0.08), 0 24px 48px -12px rgba(26, 25, 21, 0.18)",
        glow: "0 0 0 1px rgba(255, 214, 0, 0.4), 0 4px 20px -4px rgba(255, 214, 0, 0.35)",
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(10px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        "fade-in": {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-400px 0" },
          "100%": { backgroundPosition: "400px 0" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.45s cubic-bezier(0.16, 1, 0.3, 1) both",
        "fade-in": "fade-in 0.3s ease both",
        shimmer: "shimmer 1.6s linear infinite",
      },
    },
  },
  plugins: [],
};

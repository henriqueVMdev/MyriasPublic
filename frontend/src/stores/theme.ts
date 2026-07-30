import { defineStore } from "pinia";
import { ref, watch } from "vue";

export type ThemeMode = "light" | "dark";

const STORAGE_KEY = "myrias-theme";

function detectInitial(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === "light" || saved === "dark") return saved;
  // Padrão: tema claro. Se o usuário escolher manualmente, a escolha é persistida.
  return "light";
}

function apply(mode: ThemeMode) {
  const root = document.documentElement;
  if (mode === "dark") root.classList.add("dark");
  else root.classList.remove("dark");
}

export const useThemeStore = defineStore("theme", () => {
  const mode = ref<ThemeMode>(detectInitial());
  apply(mode.value);

  watch(mode, (v) => {
    localStorage.setItem(STORAGE_KEY, v);
    apply(v);
  });

  function toggle() {
    mode.value = mode.value === "dark" ? "light" : "dark";
  }

  function set(v: ThemeMode) {
    mode.value = v;
  }

  return { mode, toggle, set };
});

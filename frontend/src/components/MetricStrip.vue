<script setup lang="ts">
import { computed } from "vue";

// Faixa única de métricas/KPIs: um card sem borda, com as laterais em degradê
// até a cor do fundo da página (parece fundir com o fundo) e as métricas
// separadas só por linhas discretas. Use com <MetricStripItem> no slot.
const props = defineProps<{ cols?: number }>();

// Mapas estáticos pro Tailwind não purgar as classes. A faixa fica em 1 linha
// no desktop (lg) — aí só as divisórias verticais aparecem.
const COLS: Record<number, string> = {
  2: "grid-cols-2",
  3: "grid-cols-2 sm:grid-cols-3",
  4: "grid-cols-2 lg:grid-cols-4",
  5: "grid-cols-2 md:grid-cols-3 lg:grid-cols-5",
  6: "grid-cols-2 md:grid-cols-3 lg:grid-cols-6",
};
const gridCols = computed(() => COLS[props.cols ?? 5] ?? COLS[5]);
</script>

<template>
  <div
    class="rounded-2xl overflow-hidden
           bg-[linear-gradient(to_right,#FAFAF6,#ffffff_30%,#ffffff_70%,#FAFAF6)]
           dark:bg-[linear-gradient(to_right,#111111,#18181b_30%,#18181b_70%,#111111)]"
  >
    <div
      class="grid divide-x divide-y lg:divide-y-0 divide-gray-200/60 dark:divide-zinc-700/40 stagger-children"
      :class="gridCols"
    >
      <slot />
    </div>
  </div>
</template>

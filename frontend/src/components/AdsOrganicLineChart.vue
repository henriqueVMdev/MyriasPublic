<script setup lang="ts">
import { ref, computed } from "vue";
import { Megaphone } from "lucide-vue-next";

// Gráfico de linha: unidades vendidas por dia, separadas em orgânicas vs
// atribuídas ao Mercado Ads. `sales` = total/dia; `ads` = unidades de ads/dia
// (mesmo tamanho de `dates`). Orgânica = total − ads (clampado em 0).
const props = withDefaults(
  defineProps<{
    dates: string[];
    sales: number[];
    ads?: number[] | null;
    title?: string;
  }>(),
  { title: "Vendas orgânicas vs por anúncios" }
);

const adsArr = computed(() => props.sales.map((_, i) => Math.max(0, props.ads?.[i] ?? 0)));
const organic = computed(() =>
  props.sales.map((s, i) => Math.max(0, (s || 0) - (adsArr.value[i] || 0)))
);
const n = computed(() => props.dates.length);
const maxY = computed(() => Math.max(1, ...organic.value, ...adsArr.value));
const organicTotal = computed(() => organic.value.reduce((a, b) => a + b, 0));
const adsTotal = computed(() => adsArr.value.reduce((a, b) => a + b, 0));
const total = computed(() => organicTotal.value + adsTotal.value);
const hasData = computed(() => total.value > 0);

// Coordenadas no espaço do viewBox (0..100); preserveAspectRatio="none" estica
// e `vector-effect=non-scaling-stroke` mantém a espessura da linha constante.
const Y_TOP = 6;
const Y_BOT = 94;
function x(i: number): number {
  return n.value > 1 ? (i / (n.value - 1)) * 100 : 50;
}
function y(v: number): number {
  return Y_BOT - (v / maxY.value) * (Y_BOT - Y_TOP);
}
function pts(arr: number[]): string {
  return arr.map((v, i) => `${x(i).toFixed(2)},${y(v).toFixed(2)}`).join(" ");
}

const hoverIdx = ref<number | null>(null);
const tip = ref<{ x: number; y: number } | null>(null);
function onMove(e: MouseEvent) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
  const f = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
  hoverIdx.value = n.value > 1 ? Math.round(f * (n.value - 1)) : 0;
  tip.value = { x: e.clientX, y: e.clientY };
}
function onLeave() {
  hoverIdx.value = null;
  tip.value = null;
}

function fmtDate(iso: string): string {
  const [, mm, dd] = iso.slice(0, 10).split("-");
  return `${dd}/${mm}`;
}
function pct(v: number): number {
  return total.value > 0 ? (v / total.value) * 100 : 0;
}
</script>

<template>
  <div class="bg-white rounded-xl border shadow-sm p-5">
    <h3 class="text-sm font-semibold text-gray-700 mb-1 flex items-center gap-1.5">
      <Megaphone :size="15" class="text-purple-600 dark:text-purple-400" /> {{ title }}
    </h3>
    <p class="text-[11px] text-gray-400 mb-3">
      Unidades vendidas por dia: atribuídas ao Mercado Ads vs orgânicas. A atribuição do ML
      tem janela própria — pode não casar 1:1 com os pedidos.
    </p>

    <div v-if="!hasData" class="text-sm text-gray-500 text-center py-8">
      Nenhuma venda no período.
    </div>

    <template v-else>
      <div class="relative h-40" @mousemove="onMove" @mouseleave="onLeave">
        <svg class="w-full h-full" viewBox="0 0 100 100" preserveAspectRatio="none">
          <line
            v-if="hoverIdx !== null"
            :x1="x(hoverIdx)" :x2="x(hoverIdx)" :y1="Y_TOP" :y2="Y_BOT"
            class="stroke-gray-300 dark:stroke-zinc-600"
            stroke-width="1" stroke-dasharray="3 3" vector-effect="non-scaling-stroke"
          />
          <polyline
            :points="pts(organic)" fill="none"
            class="stroke-green-500"
            stroke-width="2" stroke-linejoin="round" stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
          <polyline
            :points="pts(adsArr)" fill="none"
            class="stroke-purple-500"
            stroke-width="2" stroke-linejoin="round" stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
        </svg>
      </div>

      <div class="flex flex-wrap gap-x-6 gap-y-1 mt-3 text-xs">
        <span class="flex items-center gap-1.5 text-gray-600">
          <span class="w-3 h-0.5 rounded bg-green-500"></span> Orgânicas
          <strong class="text-gray-800 tabular-nums">{{ organicTotal.toLocaleString("pt-BR") }}</strong>
          <span class="text-gray-400 tabular-nums">({{ pct(organicTotal).toFixed(0) }}%)</span>
        </span>
        <span class="flex items-center gap-1.5 text-gray-600">
          <span class="w-3 h-0.5 rounded bg-purple-500"></span> Por anúncios
          <strong class="text-gray-800 tabular-nums">{{ adsTotal.toLocaleString("pt-BR") }}</strong>
          <span class="text-gray-400 tabular-nums">({{ pct(adsTotal).toFixed(0) }}%)</span>
        </span>
      </div>
    </template>

    <div
      v-if="tip && hoverIdx !== null"
      class="fixed z-50 pointer-events-none px-2 py-1 rounded-md bg-gray-900 text-white text-xs whitespace-nowrap shadow-lg"
      :style="{ left: tip.x + 12 + 'px', top: tip.y - 8 + 'px' }"
    >
      <div class="font-semibold mb-0.5">{{ fmtDate(dates[hoverIdx]) }}</div>
      <div><span class="text-green-300">Orgânicas:</span> {{ organic[hoverIdx] }}</div>
      <div><span class="text-purple-300">Por ads:</span> {{ adsArr[hoverIdx] }}</div>
    </div>
  </div>
</template>

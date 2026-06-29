<script setup lang="ts">
import { computed, ref } from "vue";
import type { PerformanceData } from "@/api/dashboard";

const props = defineProps<{ data: PerformanceData }>();

function pct(v: number, decimals = 1): string {
  return (v * 100).toLocaleString("pt-BR", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }) + "%";
}

function fmt(n: number): string {
  return n.toLocaleString("pt-BR");
}

const hasData = computed(() =>
  props.data.combined.total_visits > 0 ||
  props.data.combined.total_sales > 0 ||
  props.data.combined.total_questions > 0
);

// Ratio series per day computed from combined raw series
const ratioData = computed(() => {
  const { combined, dates } = props.data;
  const n = dates.length;
  const salesPerVisit: (number | null)[] = [];
  const questionsPerVisit: (number | null)[] = [];
  const salesPerQuestion: (number | null)[] = [];
  for (let i = 0; i < n; i++) {
    const v = combined.visits_series[i] ?? 0;
    const s = combined.sales_series[i] ?? 0;
    const q = combined.questions_series[i] ?? 0;
    salesPerVisit.push(v > 0 ? s / v : null);
    questionsPerVisit.push(v > 0 ? q / v : null);
    salesPerQuestion.push(q > 0 ? s / q : null);
  }
  return { salesPerVisit, questionsPerVisit, salesPerQuestion };
});

// Chart constants
const WIDTH = 700;
const HEIGHT = 210;
const PAD_L = 52;
const PAD_R = 12;
const PAD_T = 12;
const PAD_B = 32;
const innerW = WIDTH - PAD_L - PAD_R;
const innerH = HEIGHT - PAD_T - PAD_B;

type SeriesId = "spv" | "qpv" | "spq";

const SERIES_DEF = computed<Array<{ id: SeriesId; label: string; color: string; values: (number | null)[] }>>(() => [
  { id: "spv", label: "Vendas/Visita",    color: "#3483FA", values: ratioData.value.salesPerVisit },
  { id: "qpv", label: "Perg./Visita",     color: "#7C3AED", values: ratioData.value.questionsPerVisit },
  { id: "spq", label: "Vendas/Pergunta",  color: "#059669", values: ratioData.value.salesPerQuestion },
]);

const active = ref(new Set<SeriesId>(["spv", "qpv", "spq"]));

function toggle(id: SeriesId) {
  const next = new Set(active.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  active.value = next;
}

const maxRatio = computed(() => {
  let max = 0;
  for (const s of SERIES_DEF.value) {
    if (!active.value.has(s.id)) continue;
    for (const v of s.values) {
      if (v !== null && v > max) max = v;
    }
  }
  return max > 0 ? max : 0.01;
});

const yTicks = computed(() => {
  const m = maxRatio.value;
  return [0, m * 0.25, m * 0.5, m * 0.75, m];
});

function xPos(i: number, n: number): number {
  if (n <= 1) return PAD_L + innerW / 2;
  return PAD_L + (i / (n - 1)) * innerW;
}

function yPos(v: number): number {
  return PAD_T + innerH - (v / maxRatio.value) * innerH;
}

function pathFor(values: (number | null)[]): string {
  let path = "";
  let inSeg = false;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v === null) { inSeg = false; continue; }
    const px = xPos(i, values.length).toFixed(1);
    const py = yPos(v).toFixed(1);
    if (!inSeg) { path += `M${px},${py}`; inSeg = true; }
    else path += ` L${px},${py}`;
  }
  return path;
}

const xLabels = computed(() => {
  const n = props.data.dates.length;
  if (n === 0) return [] as string[];
  const maxLabels = 7;
  const step = Math.max(1, Math.ceil(n / maxLabels));
  const showAt = new Set<number>();
  for (let i = n - 1; i >= 0; i -= step) showAt.add(i);
  if (n > 1 && !showAt.has(0)) {
    let nearest = -1;
    for (const i of showAt) {
      if (i < step && (nearest === -1 || i < nearest)) nearest = i;
    }
    if (nearest !== -1) showAt.delete(nearest);
    showAt.add(0);
  }
  return props.data.dates.map((d, i) => {
    if (!showAt.has(i)) return "";
    const [, m, day] = d.split("-");
    return `${day}/${m}`;
  });
});

const WEEKDAYS = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"];

function tooltipDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  const weekday = WEEKDAYS[date.getUTCDay()];
  return `${weekday}, ${d.toString().padStart(2, "0")}/${m.toString().padStart(2, "0")}/${y}`;
}

const hoverIndex = ref<number | null>(null);

function onHover(e: MouseEvent) {
  const svg = e.currentTarget as SVGSVGElement;
  const rect = svg.getBoundingClientRect();
  const px = ((e.clientX - rect.left) / rect.width) * WIDTH;
  if (px < PAD_L || px > WIDTH - PAD_R) { hoverIndex.value = null; return; }
  const n = props.data.dates.length;
  const ratio = (px - PAD_L) / innerW;
  hoverIndex.value = Math.max(0, Math.min(n - 1, Math.round(ratio * (n - 1))));
}

function onLeave() { hoverIndex.value = null; }

function yTickLabel(t: number): string {
  const p = t * 100;
  return p === 0 ? "0%" : p < 1 ? p.toFixed(2) + "%" : p.toFixed(1) + "%";
}
</script>

<template>
  <div>
    <!-- ── Totais ────────────────────────────────────────────── -->
    <div class="grid grid-cols-3 gap-2 mb-3">
      <div class="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-center">
        <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-1">Visitas</p>
        <p class="text-xl font-bold text-gray-800">{{ fmt(data.combined.total_visits) }}</p>
      </div>
      <div class="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-center">
        <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-1">Vendas</p>
        <p class="text-xl font-bold text-gray-800">{{ fmt(data.combined.total_sales) }}</p>
      </div>
      <div class="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-center">
        <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-1">Perguntas</p>
        <p class="text-xl font-bold text-gray-800">{{ fmt(data.combined.total_questions) }}</p>
      </div>
    </div>

    <!-- ── Taxas de conversão ─────────────────────────────────── -->
    <div class="grid grid-cols-3 gap-2 mb-4">
      <!-- Vendas / Visita -->
      <div class="bg-white border border-gray-200 rounded-lg overflow-hidden flex">
        <div class="w-1 shrink-0 bg-[#3483FA]"></div>
        <div class="px-4 py-3 flex-1 min-w-0">
          <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-0.5">Vendas / Visita</p>
          <p class="text-2xl font-bold text-[#3483FA] leading-none">
            {{ pct(data.combined.sales_per_visit) }}
          </p>
          <p class="text-[11px] text-gray-400 mt-1">
            {{ fmt(data.combined.total_sales) }} de {{ fmt(data.combined.total_visits) }} visitas
          </p>
        </div>
      </div>

      <!-- Perguntas / Visita -->
      <div class="bg-white border border-gray-200 rounded-lg overflow-hidden flex">
        <div class="w-1 shrink-0 bg-[#7C3AED]"></div>
        <div class="px-4 py-3 flex-1 min-w-0">
          <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-0.5">Perg. / Visita</p>
          <p class="text-2xl font-bold text-[#7C3AED] leading-none">
            {{ pct(data.combined.questions_per_visit) }}
          </p>
          <p class="text-[11px] text-gray-400 mt-1">
            {{ fmt(data.combined.total_questions) }} de {{ fmt(data.combined.total_visits) }} visitas
          </p>
        </div>
      </div>

      <!-- Vendas / Pergunta -->
      <div class="bg-white border border-gray-200 rounded-lg overflow-hidden flex">
        <div class="w-1 shrink-0 bg-[#059669]"></div>
        <div class="px-4 py-3 flex-1 min-w-0">
          <p class="text-[10px] font-semibold uppercase tracking-widest text-gray-400 mb-0.5">Vendas / Pergunta</p>
          <p class="text-2xl font-bold text-[#059669] leading-none">
            {{ pct(data.combined.sales_per_question) }}
          </p>
          <p class="text-[11px] text-gray-400 mt-1">
            {{ fmt(data.combined.total_sales) }} de {{ fmt(data.combined.total_questions) }} perguntas
          </p>
        </div>
      </div>
    </div>

    <!-- ── Gráfico ─────────────────────────────────────────────── -->
    <div
      v-if="!hasData"
      class="text-center text-sm text-gray-400 py-10 border border-dashed border-gray-200 rounded-lg"
    >
      Nenhum dado de visitas ou perguntas no período.<br>
      <span class="text-xs text-gray-400">Verifique os logs do backend — os totais de vendas acima devem coincidir com o gráfico de faturamento.</span>
    </div>

    <template v-else>
      <!-- Legenda -->
      <div class="flex flex-wrap gap-2 mb-3">
        <button
          v-for="s in SERIES_DEF"
          :key="s.id"
          type="button"
          @click="toggle(s.id)"
          class="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border transition-all"
          :class="active.has(s.id)
            ? 'bg-white border-gray-300 text-gray-700'
            : 'bg-gray-50 border-gray-200 text-gray-400 opacity-60'"
        >
          <span class="w-2.5 h-2.5 rounded-full shrink-0" :style="{ background: s.color }"></span>
          {{ s.label }}
        </button>
      </div>

      <!-- SVG line chart -->
      <div class="relative">
        <svg
          :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
          class="w-full h-auto"
          @mousemove="onHover"
          @mouseleave="onLeave"
        >
          <!-- Grid Y + labels -->
          <g>
            <line
              v-for="(t, i) in yTicks" :key="`gy-${i}`"
              :x1="PAD_L" :x2="WIDTH - PAD_R"
              :y1="yPos(t)" :y2="yPos(t)"
              stroke="#e5e7eb" stroke-dasharray="2 3"
            />
            <text
              v-for="(t, i) in yTicks" :key="`yl-${i}`"
              :x="PAD_L - 4" :y="yPos(t) + 3"
              text-anchor="end" font-size="10" fill="#9ca3af"
            >{{ yTickLabel(t) }}</text>
          </g>

          <!-- X labels -->
          <g>
            <text
              v-for="(label, i) in xLabels" :key="`xl-${i}`"
              :x="xPos(i, xLabels.length)" :y="HEIGHT - 10"
              text-anchor="middle" font-size="10" fill="#9ca3af"
            >{{ label }}</text>
          </g>

          <!-- Lines -->
          <g>
            <path
              v-for="s in SERIES_DEF" :key="`path-${s.id}`"
              v-show="active.has(s.id)"
              :d="pathFor(s.values)"
              :stroke="s.color"
              stroke-width="2"
              fill="none"
              stroke-linejoin="round"
              stroke-linecap="round"
            />
          </g>

          <!-- Hover -->
          <g v-if="hoverIndex !== null">
            <line
              :x1="xPos(hoverIndex, data.dates.length)"
              :x2="xPos(hoverIndex, data.dates.length)"
              :y1="PAD_T" :y2="HEIGHT - PAD_B"
              stroke="#d1d5db" stroke-dasharray="3 3"
            />
            <template v-for="s in SERIES_DEF" :key="`dot-${s.id}`">
              <circle
                v-if="active.has(s.id) && s.values[hoverIndex] !== null"
                :cx="xPos(hoverIndex, data.dates.length)"
                :cy="yPos(s.values[hoverIndex]!)"
                r="3.5" :fill="s.color" stroke="white" stroke-width="1.5"
              />
            </template>
          </g>
        </svg>

        <!-- Tooltip -->
        <div
          v-if="hoverIndex !== null"
          class="absolute top-2 left-2 bg-white border border-gray-200 rounded-lg shadow-md p-2.5 text-xs pointer-events-none min-w-[170px]"
        >
          <p class="font-semibold text-gray-700 mb-1.5">{{ tooltipDate(data.dates[hoverIndex]) }}</p>
          <div
            v-for="s in SERIES_DEF" :key="`tt-${s.id}`"
            class="flex items-center justify-between gap-3 py-0.5"
            :class="{ 'opacity-30': !active.has(s.id) }"
          >
            <span class="flex items-center gap-1.5 text-gray-500">
              <span class="w-2 h-2 rounded-full shrink-0" :style="{ background: s.color }"></span>
              {{ s.label }}
            </span>
            <span class="font-semibold" :style="{ color: s.color }">
              {{ s.values[hoverIndex] !== null ? pct(s.values[hoverIndex]!, 2) : "—" }}
            </span>
          </div>
          <div class="mt-2 pt-2 border-t border-gray-100 text-gray-400 space-y-0.5">
            <div class="flex justify-between">
              <span>Visitas</span>
              <span class="font-medium text-gray-600">{{ fmt(data.combined.visits_series[hoverIndex] ?? 0) }}</span>
            </div>
            <div class="flex justify-between">
              <span>Vendas</span>
              <span class="font-medium text-gray-600">{{ fmt(data.combined.sales_series[hoverIndex] ?? 0) }}</span>
            </div>
            <div class="flex justify-between">
              <span>Perguntas</span>
              <span class="font-medium text-gray-600">{{ fmt(data.combined.questions_series[hoverIndex] ?? 0) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

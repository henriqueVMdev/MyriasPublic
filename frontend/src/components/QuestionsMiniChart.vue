<script setup lang="ts">
import { computed, ref } from "vue";

const props = defineProps<{
  title: string;
  subtitle?: string;
  series: Array<{ label: string; count: number }>;
  color?: string;
  period: "day" | "month";
}>();

const color = computed(() => props.color || "#3483FA");

// SVG IDs não podem ter espaços ou caracteres especiais
const gradientId = computed(() =>
  "grad-" + props.title.replace(/[^a-zA-Z0-9_-]/g, "_")
);

const W = 320;
const H = 140;
const PAD_L = 30;
const PAD_R = 10;
const PAD_T = 10;
const PAD_B = 22;
const innerW = W - PAD_L - PAD_R;
const innerH = H - PAD_T - PAD_B;

const maxValue = computed(() => {
  let max = 0;
  for (const p of props.series) if (p.count > max) max = p.count;
  return max || 1;
});

const total = computed(() => props.series.reduce((a, b) => a + b.count, 0));

function x(i: number, n: number): number {
  if (n <= 1) return PAD_L + innerW / 2;
  return PAD_L + (i / (n - 1)) * innerW;
}
function y(v: number): number {
  return PAD_T + innerH - (v / maxValue.value) * innerH;
}

// Curva suave (Catmull-Rom → Bezier)
function smoothPath(values: number[]): string {
  if (!values.length) return "";
  const pts = values.map((v, i) => ({ x: x(i, values.length), y: y(v) }));
  if (pts.length === 1) return `M${pts[0].x},${pts[0].y}`;
  let d = `M${pts[0].x.toFixed(1)},${pts[0].y.toFixed(1)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] || p2;
    const cp1x = p1.x + (p2.x - p0.x) / 6;
    const cp1y = p1.y + (p2.y - p0.y) / 6;
    const cp2x = p2.x - (p3.x - p1.x) / 6;
    const cp2y = p2.y - (p3.y - p1.y) / 6;
    d += ` C${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`;
  }
  return d;
}

const pathD = computed(() => smoothPath(props.series.map((s) => s.count)));

// Labels do eixo X: mostra só alguns pra não poluir
const xLabels = computed(() => {
  const n = props.series.length;
  const maxLabels = props.period === "month" ? 6 : 6;
  const step = Math.max(1, Math.ceil(n / maxLabels));
  return props.series.map((s, i) => {
    if (i % step !== 0 && i !== n - 1) return "";
    return shortLabel(s.label);
  });
});

function shortLabel(label: string): string {
  // yyyy-mm-dd → dd/mm, yyyy-mm → mm/aa
  if (/^\d{4}-\d{2}-\d{2}$/.test(label)) {
    const [, m, d] = label.split("-");
    return `${d}/${m}`;
  }
  if (/^\d{4}-\d{2}$/.test(label)) {
    const [y, m] = label.split("-");
    return `${m}/${y.slice(2)}`;
  }
  return label;
}

const yTicks = computed(() => {
  const m = maxValue.value;
  if (m <= 1) return [0];
  return [0, Math.round(m * 0.5), m];
});

const hoverIdx = ref<number | null>(null);
function onHover(e: MouseEvent) {
  const svg = e.currentTarget as SVGSVGElement;
  const rect = svg.getBoundingClientRect();
  const px = ((e.clientX - rect.left) / rect.width) * W;
  if (px < PAD_L || px > W - PAD_R) {
    hoverIdx.value = null;
    return;
  }
  const n = props.series.length;
  const ratio = (px - PAD_L) / innerW;
  hoverIdx.value = Math.max(0, Math.min(n - 1, Math.round(ratio * (n - 1))));
}
function onLeave() {
  hoverIdx.value = null;
}
</script>

<template>
  <div class="bg-white rounded-xl border shadow-sm p-4">
    <div class="flex items-baseline justify-between mb-2">
      <div>
        <h4 class="font-semibold text-sm text-gray-900 truncate max-w-[14rem]">
          {{ title }}
        </h4>
        <p v-if="subtitle" class="text-[10px] text-gray-400">{{ subtitle }}</p>
      </div>
      <span class="text-xs font-semibold text-gray-600">
        total {{ total }}
      </span>
    </div>

    <div class="relative">
      <svg
        :viewBox="`0 0 ${W} ${H}`"
        class="w-full h-auto"
        @mousemove="onHover"
        @mouseleave="onLeave"
      >
        <g>
          <line
            v-for="(t, i) in yTicks"
            :key="`gy-${i}`"
            :x1="PAD_L"
            :x2="W - PAD_R"
            :y1="y(t)"
            :y2="y(t)"
            stroke="#e5e7eb"
            stroke-dasharray="2 3"
          />
          <text
            v-for="(t, i) in yTicks"
            :key="`yl-${i}`"
            :x="PAD_L - 4"
            :y="y(t) + 3"
            text-anchor="end"
            font-size="9"
            fill="#9ca3af"
          >
            {{ t }}
          </text>
        </g>

        <g>
          <text
            v-for="(lbl, i) in xLabels"
            :key="`xl-${i}`"
            :x="x(i, xLabels.length)"
            :y="H - 6"
            text-anchor="middle"
            font-size="9"
            fill="#9ca3af"
          >
            {{ lbl }}
          </text>
        </g>

        <!-- Área sombreada -->
        <defs>
          <linearGradient :id="gradientId" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" :stop-color="color" stop-opacity="0.2" />
            <stop offset="100%" :stop-color="color" stop-opacity="0" />
          </linearGradient>
        </defs>
        <path
          :d="pathD + ` L${W - PAD_R},${PAD_T + innerH} L${PAD_L},${PAD_T + innerH} Z`"
          :fill="`url(#${gradientId})`"
        />

        <!-- Curva -->
        <path
          :d="pathD"
          :stroke="color"
          stroke-width="2"
          fill="none"
          stroke-linejoin="round"
        />

        <!-- Hover -->
        <g v-if="hoverIdx !== null">
          <line
            :x1="x(hoverIdx, series.length)"
            :x2="x(hoverIdx, series.length)"
            :y1="PAD_T"
            :y2="H - PAD_B"
            stroke="#9ca3af"
            stroke-dasharray="3 3"
          />
          <circle
            :cx="x(hoverIdx, series.length)"
            :cy="y(series[hoverIdx].count)"
            r="3.5"
            :fill="color"
            stroke="white"
            stroke-width="1.5"
          />
        </g>
      </svg>

      <div
        v-if="hoverIdx !== null"
        class="absolute top-1 left-1 bg-white border rounded shadow-sm px-2 py-1 text-[10px] pointer-events-none"
      >
        <span class="text-gray-600">{{ series[hoverIdx].label }}:</span>
        <span class="font-semibold ml-1">{{ series[hoverIdx].count }}</span>
      </div>

      <div
        v-if="total === 0"
        class="absolute inset-0 flex items-center justify-center text-[11px] text-gray-400"
      >
        Sem perguntas neste período
      </div>
    </div>
  </div>
</template>

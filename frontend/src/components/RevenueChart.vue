<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { DashboardRevenue } from "@/api/dashboard";

const props = defineProps<{ data: DashboardRevenue }>();

type SeriesKey = "combined" | number; // "combined" ou user_id

const active = ref<Set<SeriesKey>>(
  new Set<SeriesKey>(["combined", ...props.data.accounts.map((a) => a.user_id)])
);
const hoverIndex = ref<number | null>(null);

// Reconcilia `active` quando os dados mudam: novas contas entram ativadas,
// contas removidas saem do Set, preferências de toggle das contas que
// continuam são preservadas. Também reseta hoverIndex pra evitar acessar
// índices fora do novo range.
watch(
  () => props.data,
  (next) => {
    const validKeys = new Set<SeriesKey>([
      "combined",
      ...next.accounts.map((a) => a.user_id),
    ]);
    const reconciled = new Set<SeriesKey>();
    // Mantém estado do que já existia
    for (const k of active.value) {
      if (validKeys.has(k)) reconciled.add(k);
    }
    // Adiciona contas/combined novas (default: ativadas)
    for (const k of validKeys) {
      if (!active.value.has(k) && !reconciled.has(k)) reconciled.add(k);
    }
    // Garante que pelo menos "combined" continue ativo se ele existir
    if (validKeys.has("combined") && reconciled.size === 0) {
      reconciled.add("combined");
    }
    active.value = reconciled;
    hoverIndex.value = null;
  },
  { deep: false }
);

function toggle(key: SeriesKey) {
  const next = new Set(active.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  active.value = next;
}

// Paleta — combinado em preto, cada conta com sua cor
const ACCOUNT_COLORS = ["#3483FA", "#9333EA", "#059669", "#EA580C"];
const COMBINED_COLOR = "#6b7280";

const series = computed(() => {
  const out: Array<{
    key: SeriesKey;
    label: string;
    color: string;
    values: number[];
    total: number;
    avg_ticket: number;
    total_orders: number;
    avg_orders_per_day: number;
    avg_revenue_per_day: number;
  }> = [];
  out.push({
    key: "combined",
    label: "Combinado",
    color: COMBINED_COLOR,
    values: props.data.combined,
    total: props.data.combined_total,
    avg_ticket: props.data.combined_avg_ticket,
    total_orders: props.data.combined_total_orders,
    avg_orders_per_day: props.data.combined_avg_orders_per_day,
    avg_revenue_per_day: props.data.combined_avg_revenue_per_day,
  });
  props.data.accounts.forEach((a, i) => {
    out.push({
      key: a.user_id,
      label: a.nickname,
      color: ACCOUNT_COLORS[i % ACCOUNT_COLORS.length],
      values: a.series,
      total: a.total,
      avg_ticket: a.avg_ticket,
      total_orders: a.total_orders,
      avg_orders_per_day: a.avg_orders_per_day,
      avg_revenue_per_day: a.avg_revenue_per_day,
    });
  });
  return out;
});

const WIDTH = 700;
const HEIGHT = 240;
const PAD_L = 56;
const PAD_R = 12;
const PAD_T = 12;
const PAD_B = 32;
const innerW = WIDTH - PAD_L - PAD_R;
const innerH = HEIGHT - PAD_T - PAD_B;

// `series` filtrado pra só ativas — usado várias vezes no template/hover.
const activeSeries = computed(() =>
  series.value.filter((s) => active.value.has(s.key))
);

const hasActiveSeries = computed(() => activeSeries.value.length > 0);

const maxValue = computed(() => {
  let max = 0;
  for (const s of activeSeries.value) {
    for (const v of s.values) if (typeof v === "number" && v > max) max = v;
  }
  return max || 1;
});

// Hover só é considerado válido se o índice cabe no range atual.
const validHoverIndex = computed<number | null>(() => {
  if (hoverIndex.value === null) return null;
  if (hoverIndex.value < 0 || hoverIndex.value >= props.data.dates.length) return null;
  return hoverIndex.value;
});

function x(i: number, n: number): number {
  if (n <= 1) return PAD_L + innerW / 2;
  return PAD_L + (i / (n - 1)) * innerW;
}

function y(v: number): number {
  return PAD_T + innerH - (v / maxValue.value) * innerH;
}

function pathFor(values: number[]): string {
  if (!values.length) return "";
  return values
    .map((v, i) => `${i === 0 ? "M" : "L"}${x(i, values.length).toFixed(1)},${y(v).toFixed(1)}`)
    .join(" ");
}

const yTicks = computed(() => {
  const m = maxValue.value;
  return [0, m * 0.25, m * 0.5, m * 0.75, m];
});

function formatBRL(v: number): string {
  // Usado no eixo Y — mantém compacto, sem casas decimais.
  return v.toLocaleString("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  });
}

function formatBRLFull(v: number): string {
  // Usado no tooltip e totais da legenda — mostra valor exato.
  return v.toLocaleString("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function shortDate(iso: string): string {
  // yyyy-mm-dd → dd/mm
  const [, m, d] = iso.split("-");
  return `${d}/${m}`;
}

const WEEKDAYS = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"];

function tooltipDate(iso: string): string {
  // Constrói a data em UTC para evitar off-by-one de fuso horário
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  const weekday = WEEKDAYS[date.getUTCDay()];
  return `${weekday}, ${d.toString().padStart(2, "0")}/${m.toString().padStart(2, "0")}/${y}`;
}

// Reduz labels do eixo X pra não sobrepor.
// Ancora no último ponto e vai pra trás de step em step. Garante que o
// PRIMEIRO ponto também sempre apareça — usuário precisa ver o início do
// período. Se primeiro e penúltimo-mostrado ficariam grudados, primeiro
// vence (é referência mais útil).
const xLabels = computed(() => {
  const n = props.data.dates.length;
  const maxLabels = 7;
  if (n === 0) return [] as string[];
  const step = Math.max(1, Math.ceil(n / maxLabels));
  const showAt = new Set<number>();
  for (let i = n - 1; i >= 0; i -= step) showAt.add(i);
  // Garante o primeiro: se a primeira posição na sequência for muito
  // próxima de 0 (a < step), substitui por 0 pra não duplicar visualmente.
  if (n > 1 && !showAt.has(0)) {
    let nearest = -1;
    for (const i of showAt) {
      if (i < step && (nearest === -1 || i < nearest)) nearest = i;
    }
    if (nearest !== -1) showAt.delete(nearest);
    showAt.add(0);
  }
  return props.data.dates.map((d, i) =>
    showAt.has(i) ? shortDate(d) : ""
  );
});

function onHover(e: MouseEvent) {
  const svg = e.currentTarget as SVGSVGElement;
  const rect = svg.getBoundingClientRect();
  const px = ((e.clientX - rect.left) / rect.width) * WIDTH;
  if (px < PAD_L || px > WIDTH - PAD_R) {
    hoverIndex.value = null;
    return;
  }
  const n = props.data.dates.length;
  const ratio = (px - PAD_L) / innerW;
  const idx = Math.round(ratio * (n - 1));
  hoverIndex.value = Math.max(0, Math.min(n - 1, idx));
}

function onLeave() {
  hoverIndex.value = null;
}
</script>

<template>
  <div>
    <!-- Legenda / toggles -->
    <div class="flex flex-wrap gap-2 mb-3">
      <button
        v-for="s in series"
        :key="String(s.key)"
        type="button"
        @click="toggle(s.key)"
        class="flex items-center gap-2 text-xs px-2.5 py-1 rounded-full border transition-colors"
        :class="active.has(s.key) ? 'bg-white border-gray-300' : 'bg-gray-50 border-gray-200 opacity-50'"
        :title="s.total_orders > 0
          ? `${s.total_orders} pedido${s.total_orders === 1 ? '' : 's'} · ticket médio ${formatBRLFull(s.avg_ticket)} · ${s.avg_orders_per_day.toLocaleString('pt-BR', { maximumFractionDigits: 2 })} vendas/dia · ${formatBRLFull(s.avg_revenue_per_day)}/dia`
          : 'Sem pedidos no período'"
      >
        <span
          class="w-2.5 h-2.5 rounded-full"
          :style="{ background: s.color }"
        ></span>
        <span class="font-medium">{{ s.label }}</span>
        <span class="text-gray-500">{{ formatBRLFull(s.total) }}</span>
        <span
          v-if="s.total_orders > 0"
          class="text-gray-400"
        >
          &middot; tm {{ formatBRLFull(s.avg_ticket) }}
        </span>
      </button>
    </div>

    <!-- Estado: nenhuma série ativa -->
    <div
      v-if="!hasActiveSeries"
      class="text-center text-sm text-gray-500 py-12 border border-dashed rounded-lg"
    >
      Selecione pelo menos uma série na legenda acima pra visualizar o gráfico.
    </div>

    <!-- Estado: período sem nenhuma venda -->
    <div
      v-else-if="data.combined_total === 0"
      class="text-center text-sm text-gray-500 py-12 border border-dashed rounded-lg"
    >
      Nenhuma venda registrada no período selecionado.
    </div>

    <!-- SVG -->
    <div v-else class="relative">
      <svg
        :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
        class="w-full h-auto"
        @mousemove="onHover"
        @mouseleave="onLeave"
      >
        <!-- Grid Y + labels -->
        <g>
          <line
            v-for="(t, i) in yTicks"
            :key="`gy-${i}`"
            :x1="PAD_L"
            :x2="WIDTH - PAD_R"
            :y1="y(t)"
            :y2="y(t)"
            stroke="#e5e7eb"
            stroke-dasharray="2 3"
          />
          <text
            v-for="(t, i) in yTicks"
            :key="`yl-${i}`"
            :x="PAD_L - 6"
            :y="y(t) + 3"
            text-anchor="end"
            font-size="10"
            fill="#9ca3af"
          >
            {{ formatBRL(t) }}
          </text>
        </g>

        <!-- X axis labels -->
        <g>
          <text
            v-for="(label, i) in xLabels"
            :key="`xl-${i}`"
            :x="x(i, xLabels.length)"
            :y="HEIGHT - 10"
            text-anchor="middle"
            font-size="10"
            fill="#9ca3af"
          >
            {{ label }}
          </text>
        </g>

        <!-- Linhas -->
        <g>
          <path
            v-for="s in series"
            :key="`path-${s.key}`"
            v-show="active.has(s.key)"
            :d="pathFor(s.values)"
            :stroke="s.color"
            stroke-width="2"
            fill="none"
            stroke-linejoin="round"
            stroke-linecap="round"
          />
        </g>

        <!-- Hover indicator -->
        <g v-if="validHoverIndex !== null">
          <line
            :x1="x(validHoverIndex, data.dates.length)"
            :x2="x(validHoverIndex, data.dates.length)"
            :y1="PAD_T"
            :y2="HEIGHT - PAD_B"
            stroke="#9ca3af"
            stroke-dasharray="3 3"
          />
          <circle
            v-for="s in activeSeries"
            :key="`dot-${s.key}`"
            :cx="x(validHoverIndex, data.dates.length)"
            :cy="y(s.values[validHoverIndex] ?? 0)"
            r="3.5"
            :fill="s.color"
            stroke="white"
            stroke-width="1.5"
          />
        </g>
      </svg>

      <!-- Tooltip -->
      <div
        v-if="validHoverIndex !== null"
        class="absolute top-2 left-2 bg-white border rounded-lg shadow-sm p-2 text-xs pointer-events-none"
      >
        <div class="font-semibold text-gray-700 mb-1">
          {{ tooltipDate(data.dates[validHoverIndex]) }}
        </div>
        <div
          v-for="s in activeSeries"
          :key="`tt-${s.key}`"
          class="flex items-center gap-2"
        >
          <span
            class="w-2 h-2 rounded-full"
            :style="{ background: s.color }"
          ></span>
          <span class="text-gray-600">{{ s.label }}:</span>
          <span class="font-medium">{{ formatBRLFull(s.values[validHoverIndex] ?? 0) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

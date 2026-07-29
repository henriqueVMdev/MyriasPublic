<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getPerfItem, type PerfItemDetail } from "@/api/performance";
import StatusBadge from "@/components/StatusBadge.vue";
import ShippingBadge from "@/components/ShippingBadge.vue";
import PromoPrice from "@/components/PromoPrice.vue";
import ItemAdsCard from "@/components/ItemAdsCard.vue";
import CompetitionCard from "@/components/CompetitionCard.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";
import AdsOrganicLineChart from "@/components/AdsOrganicLineChart.vue";
import {
  ArrowLeft, ExternalLink, Loader2, Eye, ShoppingCart,
  Tag, Calendar,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const itemId = route.params.id as string;

const detail = ref<PerfItemDetail | null>(null);
const loading = ref(false);
const days = ref(90);

const PERIODS = [
  { value: 7, label: "7d" },
  { value: 30, label: "30d" },
  { value: 60, label: "60d" },
  { value: 90, label: "90d" },
];

const meta = computed(() => detail.value?.meta);
const visits = computed(() => detail.value?.visits ?? { total: 0, dates: [], series: [] });
const maxVisit = computed(() => Math.max(1, ...visits.value.series));
const sales = computed(() => detail.value?.sales ?? { dates: [], series: [], revenue_series: [] });
const maxSale = computed(() => Math.max(1, ...sales.value.series));

// Tooltip flutuante compartilhado pelos gráficos (mostra dia + valor ao passar o mouse)
const tip = ref<{ x: number; y: number; text: string } | null>(null);
function showTip(e: MouseEvent, text: string) {
  tip.value = { x: e.clientX, y: e.clientY, text };
}
function hideTip() {
  tip.value = null;
}

async function load() {
  loading.value = true;
  try {
    detail.value = await getPerfItem(itemId, days.value);
  } catch (err) {
    console.error("Erro ao carregar performance do item:", err);
  } finally {
    loading.value = false;
  }
}

function setPeriod(d: number) {
  days.value = d;
  load();
}

function fmtPrice(v: number | null | undefined): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function fmtPct(v: number | null): string {
  return v != null ? (v * 100).toFixed(2) + "%" : "—";
}
function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}
const daysSince = computed(() => {
  const last = detail.value?.last_sale_date;
  if (!last) return null;
  const d = new Date(last.slice(0, 10) + "T00:00:00");
  return Math.floor((Date.now() - d.getTime()) / 86400000);
});

onMounted(load);
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-center gap-3 mb-4">
      <button @click="router.push('/items')" class="p-2 rounded-lg hover:bg-gray-200 transition-colors">
        <ArrowLeft :size="20" />
      </button>
      <div class="flex-1 min-w-0">
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight truncate">{{ meta?.title || "Performance do anúncio" }}</h2>
        <p class="text-sm text-gray-500 font-mono">{{ itemId }}</p>
      </div>
      <a
        v-if="meta?.permalink"
        :href="meta.permalink"
        target="_blank"
        class="px-3 py-1.5 border rounded-lg text-sm hover:bg-gray-50 flex items-center gap-1"
      >
        <ExternalLink :size="14" /> Ver no ML
      </a>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <template v-else-if="detail">
      <!-- Info bar -->
      <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4 flex items-center justify-between flex-wrap gap-3">
        <div class="flex items-center gap-4 flex-wrap">
          <StatusBadge v-if="meta?.status" :status="meta.status" />
          <ShippingBadge :logistic-type="meta?.logistic_type" show-padrao />
          <button
            v-if="meta?.sku"
            @click="router.push(`/items/sku/${encodeURIComponent(meta.sku)}`)"
            class="text-sm text-meli-blue hover:underline flex items-center gap-1"
          >
            <Tag :size="14" /> SKU {{ meta.sku }}
          </button>
          <span class="text-sm text-gray-500 flex items-center gap-1">
            Preço: <PromoPrice :price="meta?.price ?? null" :original="meta?.original_price" />
          </span>
          <span class="text-sm text-gray-500 flex items-center gap-1"><Calendar :size="14" /> Criado em: {{ fmtDate(meta?.date_created) }}</span>
          <span v-if="meta?.nickname" class="text-xs text-gray-400">{{ meta.nickname }}</span>
        </div>
        <div class="flex gap-1 p-1 bg-gray-100 rounded-xl">
          <button
            v-for="p in PERIODS" :key="p.value" @click="setPeriod(p.value)"
            class="px-3 py-1.5 rounded-lg text-sm font-semibold transition-colors"
            :class="days === p.value ? 'bg-meli-blue text-brand-yellow shadow-sm' : 'text-gray-500 hover:text-gray-700'"
          >{{ p.label }}</button>
        </div>
      </div>

      <!-- Métricas -->
      <MetricStrip :cols="6" class="mb-4">
        <MetricStripItem :label="`Visitas (${days}d)`">
          <div class="text-2xl font-bold">{{ visits.total.toLocaleString("pt-BR") }}</div>
          <div v-if="(detail.visits_lifetime ?? 0) !== visits.total" class="text-[11px] text-gray-400 tabular-nums" title="Máximo que o ML expõe por anúncio são ~2 anos de visitas">
            {{ (detail.visits_lifetime ?? 0).toLocaleString("pt-BR") }} no total (até 2 anos)
          </div>
        </MetricStripItem>
        <MetricStripItem :label="`Vendas (${days}d)`">
          <div class="text-2xl font-bold text-green-600">{{ detail.qty_sold }}</div>
          <div v-if="(detail.sold_lifetime ?? 0) !== detail.qty_sold" class="text-[11px] text-gray-400 tabular-nums">
            {{ (detail.sold_lifetime ?? 0).toLocaleString("pt-BR") }} no total (vitalício)
          </div>
        </MetricStripItem>
        <MetricStripItem label="Faturamento">
          <div class="text-2xl font-bold text-green-700">{{ fmtPrice(detail.revenue) }}</div>
        </MetricStripItem>
        <MetricStripItem label="Conversão">
          <div class="text-2xl font-bold">{{ fmtPct(detail.conversion) }}</div>
        </MetricStripItem>
        <MetricStripItem label="Perguntas">
          <div class="text-2xl font-bold">{{ detail.questions.total }}</div>
          <div class="text-[11px] text-gray-400">{{ fmtPct(detail.questions_per_visit) }} / visita</div>
        </MetricStripItem>
        <MetricStripItem label="Última venda">
          <div class="text-lg font-bold" :class="daysSince != null && daysSince >= 60 ? 'text-red-600' : daysSince == null ? 'text-red-600' : ''">
            {{ detail.last_sale_date ? fmtDate(detail.last_sale_date) : "Nunca" }}
          </div>
          <div v-if="daysSince != null" class="text-[11px] text-gray-400">{{ daysSince }} dias atrás</div>
        </MetricStripItem>
      </MetricStrip>

      <!-- Publicidade (Mercado Ads) — buscado sob demanda por item -->
      <ItemAdsCard :ads="detail.ads" :days="days" class="mb-4" />

      <!-- Concorrência — análise ao vivo (catálogo/buy box), buscada sob demanda -->
      <CompetitionCard :item-id="itemId" class="mb-4" />

      <!-- Gráfico de visitas -->
      <div class="bg-white rounded-xl border shadow-sm p-5 mb-4">
        <h3 class="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-1.5">
          <Eye :size="15" class="text-meli-blue dark:text-brand-yellow" /> Visitas por dia
        </h3>
        <div v-if="visits.series.length === 0" class="text-sm text-gray-500 text-center py-8">
          Sem dados de visitas no período.
        </div>
        <div v-else class="flex items-end gap-px h-40">
          <div
            v-for="(v, i) in visits.series"
            :key="i"
            class="flex-1 bg-meli-blue/70 hover:bg-meli-blue dark:bg-brand-yellow/70 dark:hover:bg-brand-yellow rounded-t transition-colors cursor-pointer"
            :style="{ height: (v / maxVisit * 100) + '%' }"
            @mousemove="showTip($event, `${fmtDate(visits.dates[i])} — ${v} ${v === 1 ? 'visita' : 'visitas'}`)"
            @mouseleave="hideTip"
          ></div>
        </div>
      </div>

      <!-- Gráfico de vendas por dia -->
      <div class="bg-white rounded-xl border shadow-sm p-5">
        <h3 class="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-1.5">
          <ShoppingCart :size="15" class="text-green-600" /> Vendas por dia ({{ days }}d)
        </h3>
        <div v-if="detail.qty_sold === 0" class="text-sm text-gray-500 text-center py-8">
          Nenhuma venda no período analisado pelo snapshot.
        </div>
        <div v-else class="flex items-end gap-px h-40">
          <div
            v-for="(q, i) in sales.series"
            :key="i"
            class="flex-1 rounded-t transition-colors cursor-pointer"
            :class="q > 0 ? 'bg-green-500/80 hover:bg-green-600' : 'bg-gray-100'"
            :style="{ height: q > 0 ? (q / maxSale * 100) + '%' : '2px' }"
            @mousemove="showTip($event, `${fmtDate(sales.dates[i])} — ${q} ${q === 1 ? 'venda' : 'vendas'}` + (sales.revenue_series[i] ? ` · ${fmtPrice(sales.revenue_series[i])}` : ''))"
            @mouseleave="hideTip"
          ></div>
        </div>
      </div>

      <!-- Vendas: orgânicas vs por anúncios (Mercado Ads) — linha por dia -->
      <AdsOrganicLineChart
        class="mt-4"
        :dates="sales.dates"
        :sales="sales.series"
        :ads="detail.ads_series"
      />
    </template>

    <!-- Tooltip flutuante dos gráficos -->
    <div
      v-if="tip"
      class="fixed z-50 pointer-events-none px-2 py-1 rounded-md bg-gray-900 text-white text-xs whitespace-nowrap shadow-lg"
      :style="{ left: tip.x + 12 + 'px', top: tip.y - 8 + 'px' }"
    >{{ tip.text }}</div>
  </div>
</template>

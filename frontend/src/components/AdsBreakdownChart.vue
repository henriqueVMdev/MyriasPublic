<script setup lang="ts">
import { computed } from "vue";
import { Megaphone } from "lucide-vue-next";
import type { AdsBreakdown } from "@/api/performance";

const props = withDefaults(
  defineProps<{
    data?: AdsBreakdown;
    title?: string;
  }>(),
  { title: "Vendas: publicidade vs orgânica" }
);

function fmt(v: number): string {
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function num(v: number | null | undefined): string {
  return (v ?? 0).toLocaleString("pt-BR");
}
function pctOrDash(v: number | null | undefined): string {
  return v == null ? "—" : `${v.toFixed(2)}%`;
}

const rows = computed(() => {
  const d = props.data;
  if (!d) return [];
  return [
    { label: "Publicidade", units: d.ads.units,     bar: "bg-purple-500", text: "text-purple-700" },
    { label: "Orgânica",    units: d.organic.units, bar: "bg-green-500",  text: "text-green-700" },
  ];
});
const total = computed(() => rows.value.reduce((s, r) => s + r.units, 0));
function pct(u: number): number {
  return total.value > 0 ? (u / total.value) * 100 : 0;
}
</script>

<template>
  <div class="bg-white rounded-xl border shadow-sm p-4">
    <h3 class="text-sm font-semibold text-gray-700 mb-1 flex items-center gap-1.5">
      <Megaphone :size="15" class="text-purple-600" /> {{ title }}
    </h3>
    <p class="text-[11px] text-gray-400 mb-3">
      Unidades atribuídas a anúncios patrocinados (direct + indirect) vs o resto.
      Atribuição do Mercado Ads — janela própria, pode não casar 1:1 com os pedidos.
    </p>
    <div v-if="!data || (data.ads.units === 0 && data.organic.units === 0)" class="text-sm text-gray-400 py-2">
      Sem vendas no escopo.
    </div>
    <template v-else>
      <div class="space-y-2">
        <div v-for="r in rows" :key="r.label" class="flex items-center gap-2">
          <span class="w-20 text-xs font-medium" :class="r.text">{{ r.label }}</span>
          <div class="flex-1 bg-gray-100 rounded-full h-4 overflow-hidden">
            <div class="h-full rounded-full" :class="r.bar" :style="{ width: pct(r.units) + '%' }"></div>
          </div>
          <span class="w-24 text-right text-xs tabular-nums text-gray-600">
            <span class="font-semibold text-gray-800">{{ r.units.toLocaleString("pt-BR") }}</span>
            ({{ pct(r.units).toFixed(0) }}%)
          </span>
        </div>
      </div>
      <!-- Custo de ads e ACOS -->
      <div class="mt-3 pt-3 border-t flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-600">
        <span>Receita ads: <strong class="text-gray-800">{{ fmt(data.ads.amount) }}</strong></span>
        <span>Custo ads: <strong class="text-gray-800">{{ fmt(data.ads.cost) }}</strong></span>
        <span v-if="data.ads.acos != null">
          ACOS: <strong :class="data.ads.acos <= 15 ? 'text-green-600' : 'text-amber-600'">{{ data.ads.acos.toFixed(1) }}%</strong>
        </span>
      </div>

      <!-- Métricas de funil: impressões → cliques → conversão -->
      <div class="mt-3 grid grid-cols-2 sm:grid-cols-3 gap-2">
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">Impressões</div>
          <div class="text-sm font-semibold text-gray-800 tabular-nums">{{ num(data.ads.prints) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">Cliques</div>
          <div class="text-sm font-semibold text-gray-800 tabular-nums">{{ num(data.ads.clicks) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">CTR</div>
          <div class="text-sm font-semibold text-gray-800 tabular-nums">{{ pctOrDash(data.ads.ctr) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">CPC</div>
          <div class="text-sm font-semibold text-gray-800 tabular-nums">{{ data.ads.cpc != null ? fmt(data.ads.cpc) : "—" }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">Conversão</div>
          <div class="text-sm font-semibold text-gray-800 tabular-nums">{{ pctOrDash(data.ads.conversion) }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

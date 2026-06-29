<script setup lang="ts">
import { computed } from "vue";
import { Megaphone } from "lucide-vue-next";
import type { ItemAds } from "@/api/performance";

const props = withDefaults(
  defineProps<{
    ads?: Partial<ItemAds> | null;
    days?: number;
    title?: string;
  }>(),
  { title: "Publicidade (Mercado Ads)" }
);

function brl(v: number | null | undefined): string {
  return (v ?? 0).toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function int(v: number | null | undefined): string {
  return (v ?? 0).toLocaleString("pt-BR");
}
function pct(v: number | null | undefined): string {
  return v == null ? "—" : `${v.toFixed(2)}%`;
}

// `ads === null` => conta sem Product Ads ou anúncio fora de ads.
// `has_activity === false` => está no Ads mas sem cliques/custo no período.
const noAds = computed(() => props.ads == null);
const hasActivity = computed(() => !!props.ads && props.ads.has_activity !== false &&
  ((props.ads.clicks ?? 0) > 0 || (props.ads.cost ?? 0) > 0 || (props.ads.prints ?? 0) > 0));
</script>

<template>
  <div class="bg-white rounded-xl border shadow-sm p-4">
    <h3 class="text-sm font-semibold text-gray-700 mb-1 flex items-center gap-1.5">
      <Megaphone :size="15" class="text-purple-600" /> {{ title }}
      <span v-if="days" class="text-[11px] font-normal text-gray-400">· {{ days }}d</span>
    </h3>

    <div v-if="noAds" class="text-sm text-gray-400 py-2">
      Sem dados de publicidade (conta sem Mercado Ads ou anúncio fora de campanha).
    </div>
    <div v-else-if="!hasActivity" class="text-sm text-gray-400 py-2">
      No Mercado Ads, mas sem cliques ou gasto no período.
    </div>
    <template v-else>
      <!-- Linha principal: gasto / receita / unidades / ACOS -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-2 mb-2">
        <div class="rounded-lg bg-purple-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-purple-400">Gasto</div>
          <div class="text-sm font-bold text-purple-800 tabular-nums">{{ brl(ads!.cost) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">Receita ads</div>
          <div class="text-sm font-bold text-gray-800 tabular-nums">{{ brl(ads!.amount) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">Un. por ads</div>
          <div class="text-sm font-bold text-gray-800 tabular-nums">{{ int(ads!.units) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 px-2.5 py-1.5">
          <div class="text-[10px] uppercase tracking-wide text-gray-400">ACOS</div>
          <div
            class="text-sm font-bold tabular-nums"
            :class="ads!.acos == null ? 'text-gray-800' : (ads!.acos <= 15 ? 'text-green-600' : 'text-amber-600')"
          >{{ pct(ads!.acos) }}</div>
        </div>
      </div>
      <!-- Funil: impressões → cliques → CTR → CPC → conversão -->
      <div class="flex flex-wrap gap-x-5 gap-y-1 text-xs text-gray-600 pt-2 border-t">
        <span v-if="ads!.prints != null">Impressões: <strong class="text-gray-800">{{ int(ads!.prints) }}</strong></span>
        <span v-if="ads!.clicks != null">Cliques: <strong class="text-gray-800">{{ int(ads!.clicks) }}</strong></span>
        <span v-if="ads!.ctr != null">CTR: <strong class="text-gray-800">{{ pct(ads!.ctr) }}</strong></span>
        <span v-if="ads!.cpc != null">CPC: <strong class="text-gray-800">{{ brl(ads!.cpc) }}</strong></span>
        <span v-if="ads!.conversion != null">Conversão: <strong class="text-gray-800">{{ pct(ads!.conversion) }}</strong></span>
      </div>
    </template>
  </div>
</template>

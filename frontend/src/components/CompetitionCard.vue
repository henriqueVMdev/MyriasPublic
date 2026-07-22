<script setup lang="ts">
import { ref, onMounted } from "vue";
import { Swords, Loader2, Trophy, ExternalLink } from "lucide-vue-next";
import { getItemCompetition, type CompetitionAnalysis } from "@/api/competition";

const props = defineProps<{ itemId: string }>();

const data = ref<CompetitionAnalysis | null>(null);
const loading = ref(false);
const error = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await getItemCompetition(props.itemId);
  } catch (e) {
    error.value = "Não foi possível carregar a concorrência.";
    console.error("Erro concorrência:", e);
  } finally {
    loading.value = false;
  }
}

const STATUS: Record<string, { label: string; cls: string }> = {
  winning: { label: "Você está ganhando o buy box", cls: "text-green-700 bg-green-100 dark:bg-green-900/30 dark:text-green-300" },
  sharing: { label: "Empate no topo", cls: "text-meli-blue bg-blue-100 dark:bg-blue-900/30 dark:text-blue-300" },
  competing: { label: "Você está perdendo o buy box", cls: "text-amber-700 bg-amber-100 dark:bg-amber-900/30 dark:text-amber-300" },
  not_listed: { label: "Você não tem oferta neste catálogo", cls: "text-gray-600 bg-gray-100 dark:bg-zinc-800 dark:text-gray-300" },
};

function fmtPrice(v: number | null | undefined): string {
  if (v == null || v === 0) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

onMounted(load);
</script>

<template>
  <div class="bg-white dark:bg-brand-black-soft rounded-xl border dark:border-zinc-800 shadow-sm p-5">
    <h3 class="text-sm font-semibold text-gray-700 dark:text-gray-200 mb-3 flex items-center gap-1.5">
      <Swords :size="15" class="text-meli-blue dark:text-brand-yellow" /> Concorrência
    </h3>

    <div v-if="loading" class="flex items-center justify-center py-8">
      <Loader2 :size="24" class="animate-spin text-meli-blue" />
    </div>

    <div v-else-if="error" class="text-sm text-red-600 text-center py-6">{{ error }}</div>

    <!-- Fora de catálogo / não encontrado: mensagem do backend -->
    <div v-else-if="data && data.mode !== 'catalog'" class="text-sm text-gray-500 text-center py-6">
      {{ data.message }}
    </div>

    <template v-else-if="data && data.mode === 'catalog'">
      <!-- Sem ofertas -->
      <div v-if="!data.competitors || data.competitors.length === 0" class="text-sm text-gray-500 text-center py-6">
        {{ data.message || "Nenhuma oferta concorrente encontrada neste produto de catálogo." }}
      </div>

      <template v-else>
        <!-- Resumo -->
        <div class="flex flex-wrap items-center gap-3 mb-4">
          <span class="px-2.5 py-1 rounded-lg text-xs font-semibold" :class="STATUS[data.status ?? 'not_listed']?.cls">
            {{ STATUS[data.status ?? 'not_listed']?.label }}
          </span>
          <span class="text-sm text-gray-500 dark:text-gray-400">
            {{ data.competitor_count }} {{ data.competitor_count === 1 ? "oferta" : "ofertas" }}
          </span>
          <span v-if="data.my_position" class="text-sm text-gray-500 dark:text-gray-400">
            Sua posição: <strong class="text-gray-800 dark:text-gray-100">{{ data.my_position }}º</strong>
          </span>
        </div>

        <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
          <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
            <div class="text-[11px] text-gray-500 dark:text-gray-400">Seu preço</div>
            <div class="text-lg font-bold">{{ fmtPrice(data.my_price) }}</div>
          </div>
          <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
            <div class="text-[11px] text-gray-500 dark:text-gray-400">Preço vencedor</div>
            <div class="text-lg font-bold">{{ fmtPrice(data.winner_price) }}</div>
          </div>
          <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
            <div class="text-[11px] text-gray-500 dark:text-gray-400">Diferença</div>
            <div class="text-lg font-bold" :class="(data.price_gap ?? 0) > 0 ? 'text-red-600' : 'text-green-600'">
              <template v-if="data.price_gap != null">
                {{ fmtPrice(data.price_gap) }}
                <span v-if="data.price_gap_pct != null" class="text-xs font-normal text-gray-400">({{ data.price_gap_pct }}%)</span>
              </template>
              <template v-else>—</template>
            </div>
          </div>
          <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
            <div class="text-[11px] text-gray-500 dark:text-gray-400">Preço p/ ganhar</div>
            <div class="text-lg font-bold text-meli-blue dark:text-brand-yellow">{{ fmtPrice(data.price_to_win) }}</div>
          </div>
        </div>

        <!-- Tabela de concorrentes -->
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-gray-500 dark:text-gray-400 border-b dark:border-zinc-800">
                <th class="py-2 pr-3 font-medium">Vendedor</th>
                <th class="py-2 px-3 font-medium text-right">Preço</th>
                <th class="py-2 px-3 font-medium text-right">Vendas</th>
                <th class="py-2 px-3 font-medium">Frete</th>
                <th class="py-2 pl-3 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="c in data.competitors"
                :key="c.item_id"
                class="border-b last:border-0 dark:border-zinc-800"
                :class="c.is_mine ? 'bg-blue-50/60 dark:bg-blue-900/10' : ''"
              >
                <td class="py-2 pr-3">
                  <div class="flex items-center gap-1.5">
                    <Trophy v-if="c.is_winner" :size="13" class="text-amber-500 shrink-0" />
                    <span class="truncate">{{ c.seller_nickname || ("Vendedor " + c.seller_id) }}</span>
                    <span v-if="c.is_mine" class="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-meli-blue text-brand-yellow">Você</span>
                  </div>
                </td>
                <td class="py-2 px-3 text-right font-semibold tabular-nums">{{ fmtPrice(c.price) }}</td>
                <td class="py-2 px-3 text-right tabular-nums text-gray-500">{{ c.sold_quantity.toLocaleString("pt-BR") }}</td>
                <td class="py-2 px-3">
                  <span v-if="c.free_shipping" class="text-xs text-green-600 font-medium">Grátis</span>
                  <span v-else class="text-xs text-gray-400">—</span>
                </td>
                <td class="py-2 pl-3 text-right">
                  <a
                    v-if="c.item_id"
                    :href="`https://www.mercadolivre.com.br/anuncio/${c.item_id}`"
                    target="_blank"
                    class="text-gray-400 hover:text-meli-blue inline-flex"
                    title="Ver anúncio"
                  ><ExternalLink :size="14" /></a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </div>
</template>

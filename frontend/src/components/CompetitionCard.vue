<script setup lang="ts">
import { ref, onMounted } from "vue";
import { Swords, Loader2, Trophy, ExternalLink, Search, TrendingUp, Flame } from "lucide-vue-next";
import {
  getItemCompetition, getCategoryDiscovery,
  type CompetitionAnalysis, type CategoryDiscovery,
} from "@/api/competition";

const props = defineProps<{ itemId: string }>();

const data = ref<CompetitionAnalysis | null>(null);
const loading = ref(false);
const error = ref("");
const codeInput = ref("");

// Descoberta de mercado (categoria) — carregada sob demanda.
const discovery = ref<CategoryDiscovery | null>(null);
const discoveryLoading = ref(false);
const discoveryOpen = ref(false);

async function toggleDiscovery() {
  discoveryOpen.value = !discoveryOpen.value;
  if (discoveryOpen.value && !discovery.value && data.value?.category_id) {
    discoveryLoading.value = true;
    try {
      discovery.value = await getCategoryDiscovery(data.value.category_id);
    } catch (e) {
      console.error("Erro descoberta de mercado:", e);
    } finally {
      discoveryLoading.value = false;
    }
  }
}

async function load(code?: string) {
  loading.value = true;
  error.value = "";
  try {
    data.value = await getItemCompetition(props.itemId, code || undefined);
  } catch (e) {
    error.value = "Não foi possível carregar a concorrência.";
    console.error("Erro concorrência:", e);
  } finally {
    loading.value = false;
  }
}

function searchByCode() {
  load(codeInput.value.trim());
}

const STATUS: Record<string, { label: string; cls: string }> = {
  winning: { label: "Você está ganhando o buy box", cls: "text-green-700 bg-green-100 dark:bg-green-900/30 dark:text-green-300" },
  sharing: { label: "Empate no topo", cls: "text-meli-blue bg-blue-100 dark:bg-blue-900/30 dark:text-blue-300" },
  competing: { label: "Você está perdendo o buy box", cls: "text-amber-700 bg-amber-100 dark:bg-amber-900/30 dark:text-amber-300" },
  not_listed: { label: "Você não tem oferta neste catálogo", cls: "text-gray-600 bg-gray-100 dark:bg-zinc-800 dark:text-gray-300" },
  cheapest: { label: "Você é o mais barato", cls: "text-green-700 bg-green-100 dark:bg-green-900/30 dark:text-green-300" },
  below_median: { label: "Abaixo da mediana", cls: "text-meli-blue bg-blue-100 dark:bg-blue-900/30 dark:text-blue-300" },
  above_median: { label: "Acima da mediana", cls: "text-amber-700 bg-amber-100 dark:bg-amber-900/30 dark:text-amber-300" },
  unknown: { label: "Sem base de comparação", cls: "text-gray-600 bg-gray-100 dark:bg-zinc-800 dark:text-gray-300" },
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

    <!-- Não encontrado -->
    <div v-else-if="data && data.mode === 'not_found'" class="text-sm text-gray-500 text-center py-6">
      {{ data.message }}
    </div>

    <!-- Avulso (fora de catálogo): comparação por busca pública -->
    <template v-else-if="data && data.mode === 'standalone'">
      <div class="flex flex-wrap items-center gap-3 mb-3">
        <span class="px-2.5 py-1 rounded-lg text-xs font-semibold" :class="STATUS[data.status ?? 'unknown']?.cls">
          {{ STATUS[data.status ?? 'unknown']?.label }}
        </span>
        <span class="text-sm text-gray-500 dark:text-gray-400">{{ data.competitor_count }} concorrentes na busca</span>
        <span v-if="data.my_position" class="text-sm text-gray-500 dark:text-gray-400">
          Sua posição: <strong class="text-gray-800 dark:text-gray-100">{{ data.my_position }}º</strong>
        </span>
      </div>

      <!-- Como os concorrentes foram encontrados + busca manual por código -->
      <div class="flex flex-wrap items-center gap-2 mb-4 text-xs">
        <template v-if="data.matched_by === 'code' && data.codes && data.codes.length">
          <span class="text-gray-500 dark:text-gray-400">Encontrados por código:</span>
          <span
            v-for="c in data.codes" :key="c.value"
            class="px-2 py-0.5 rounded bg-meli-blue/10 dark:bg-brand-yellow/10 text-meli-blue dark:text-brand-yellow font-mono"
            :title="c.label"
          >{{ c.value }}</span>
        </template>
        <span v-else class="text-gray-400">Comparação aproximada por título/categoria</span>

        <div class="flex items-center gap-1 ml-auto">
          <div class="relative">
            <Search :size="13" class="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              v-model="codeInput" @keyup.enter="searchByCode"
              placeholder="Buscar por código…"
              class="pl-7 pr-2 py-1 rounded-lg border dark:border-zinc-700 dark:bg-zinc-800 text-xs w-40 font-mono"
            />
          </div>
          <button
            @click="searchByCode" :disabled="loading || !codeInput.trim()"
            class="px-2.5 py-1 rounded-lg text-xs font-semibold bg-meli-blue text-brand-yellow disabled:opacity-40"
          >Buscar</button>
        </div>
      </div>

      <template v-if="data.competitors && data.competitors.length">
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
        <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
          <div class="text-[11px] text-gray-500 dark:text-gray-400">Seu preço</div>
          <div class="text-lg font-bold">{{ fmtPrice(data.my_price) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
          <div class="text-[11px] text-gray-500 dark:text-gray-400">Mais barato</div>
          <div class="text-lg font-bold">{{ fmtPrice(data.min_price) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
          <div class="text-[11px] text-gray-500 dark:text-gray-400">Preço mediano</div>
          <div class="text-lg font-bold">{{ fmtPrice(data.median_price) }}</div>
        </div>
        <div class="rounded-lg bg-gray-50 dark:bg-zinc-800 p-3">
          <div class="text-[11px] text-gray-500 dark:text-gray-400">Frete grátis</div>
          <div class="text-lg font-bold">{{ data.free_shipping_pct != null ? data.free_shipping_pct + "%" : "—" }}</div>
        </div>
      </div>

      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-xs text-gray-500 dark:text-gray-400 border-b dark:border-zinc-800">
              <th class="py-2 pr-3 font-medium">Concorrente</th>
              <th class="py-2 px-3 font-medium text-right">Preço</th>
              <th class="py-2 px-3 font-medium text-right">Vendas</th>
              <th class="py-2 px-3 font-medium">Frete</th>
              <th class="py-2 pl-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.competitors" :key="c.item_id" class="border-b last:border-0 dark:border-zinc-800">
              <td class="py-2 pr-3">
                <div class="truncate max-w-xs">{{ c.title || c.seller_nickname || ("Vendedor " + c.seller_id) }}</div>
                <div class="text-[11px] text-gray-400">{{ c.seller_nickname }}</div>
              </td>
              <td class="py-2 px-3 text-right font-semibold tabular-nums">{{ fmtPrice(c.price) }}</td>
              <td class="py-2 px-3 text-right tabular-nums text-gray-500">{{ c.sold_quantity.toLocaleString("pt-BR") }}</td>
              <td class="py-2 px-3">
                <span v-if="c.free_shipping" class="text-xs text-green-600 font-medium">Grátis</span>
                <span v-else class="text-xs text-gray-400">—</span>
              </td>
              <td class="py-2 pl-3 text-right">
                <a v-if="c.permalink" :href="c.permalink" target="_blank" class="text-gray-400 hover:text-meli-blue inline-flex" title="Ver anúncio">
                  <ExternalLink :size="14" />
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      </template>
      <div v-else class="text-sm text-gray-500 text-center py-6">
        {{ data.message || "Nenhum concorrente encontrado. Tente buscar por outro código acima." }}
      </div>
    </template>

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

    <!-- Descoberta de mercado (categoria): mais vendidos + termos em alta -->
    <div v-if="data && data.category_id && data.mode !== 'not_found'" class="mt-4 pt-4 border-t dark:border-zinc-800">
      <button @click="toggleDiscovery" class="text-sm font-medium text-meli-blue dark:text-brand-yellow flex items-center gap-1.5 hover:underline">
        <TrendingUp :size="15" />
        {{ discoveryOpen ? "Ocultar mercado da categoria" : "Ver mais vendidos e termos em alta da categoria" }}
      </button>

      <div v-if="discoveryOpen" class="mt-3">
        <div v-if="discoveryLoading" class="flex items-center justify-center py-6">
          <Loader2 :size="20" class="animate-spin text-meli-blue" />
        </div>
        <template v-else-if="discovery">
          <!-- Termos em alta -->
          <div v-if="discovery.trends.length" class="mb-4">
            <div class="text-xs font-semibold text-gray-500 dark:text-gray-400 mb-1.5 flex items-center gap-1"><Flame :size="13" /> Termos em alta</div>
            <div class="flex flex-wrap gap-1.5">
              <a
                v-for="t in discovery.trends" :key="t.keyword"
                :href="t.url" target="_blank"
                class="px-2 py-0.5 rounded-full text-xs bg-gray-100 dark:bg-zinc-800 hover:bg-gray-200 dark:hover:bg-zinc-700"
              >{{ t.keyword }}</a>
            </div>
          </div>
          <!-- Mais vendidos -->
          <div v-if="discovery.best_sellers.length">
            <div class="text-xs font-semibold text-gray-500 dark:text-gray-400 mb-1.5 flex items-center gap-1"><Trophy :size="13" /> Mais vendidos</div>
            <div class="space-y-1">
              <a
                v-for="b in discovery.best_sellers" :key="b.id"
                :href="b.permalink || `https://www.mercadolivre.com.br/anuncio/${b.id}`" target="_blank"
                class="flex items-center gap-2 p-1.5 rounded-lg hover:bg-gray-50 dark:hover:bg-zinc-800/50"
              >
                <span class="w-5 text-xs text-gray-400 text-right shrink-0">{{ b.position }}º</span>
                <img v-if="b.thumbnail" :src="b.thumbnail" alt="" class="w-8 h-8 rounded object-cover shrink-0" />
                <span class="text-sm truncate flex-1">{{ b.title || b.id }}</span>
                <span class="text-sm font-semibold tabular-nums shrink-0">{{ fmtPrice(b.price) }}</span>
                <span class="text-xs text-gray-400 tabular-nums shrink-0 w-16 text-right">{{ (b.sold_quantity ?? 0).toLocaleString("pt-BR") }} vend.</span>
              </a>
            </div>
          </div>
          <div v-if="!discovery.trends.length && !discovery.best_sellers.length" class="text-sm text-gray-400 text-center py-4">
            Sem dados de mercado para esta categoria.
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

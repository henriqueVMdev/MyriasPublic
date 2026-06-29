<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
  getDuplicates,
  type DuplicatesResponse,
  type DuplicateGroup,
  type DuplicatesParams,
} from "@/api/performance";
import { useAuthStore } from "@/stores/auth";
import { useSnapshotAutoRefresh } from "@/composables/useSnapshotAutoRefresh";
import SelectMenu from "@/components/SelectMenu.vue";
import {
  Search, Loader2, RefreshCw, ChevronLeft, ChevronRight,
  Copy, PencilLine, ArrowDownWideNarrow, ArrowUpNarrowWide,
} from "lucide-vue-next";

const router = useRouter();
const auth = useAuthStore();

const resp = ref<DuplicatesResponse | null>(null);
const loading = ref(false);

const filters = ref<DuplicatesParams>({
  q: "",
  account: "all",
  min_count: 2,
  sort: "count_desc",
  offset: 0,
  limit: 50,
});

const items = computed<DuplicateGroup[]>(() => resp.value?.items ?? []);
const paging = computed(() => resp.value?.paging ?? { total: 0, offset: 0, limit: 50 });
const needsRefresh = computed(() => resp.value?.needs_refresh ?? false);
const snapshot = computed(() => resp.value?.snapshot);

async function load() {
  loading.value = true;
  try {
    resp.value = await getDuplicates(filters.value);
  } catch (err) {
    console.error("Erro ao carregar repetidos:", err);
  } finally {
    loading.value = false;
  }
}

// Mesmo cache da Performance: dispara a varredura em segundo plano ao abrir.
const { autoRefreshing, trigger: triggerBgRefresh } = useSnapshotAutoRefresh({
  account: () => filters.value.account || "all",
  reload: load,
});

function doRefresh() {
  if (autoRefreshing.value) return;
  triggerBgRefresh("full");
}

function applyFilters() {
  filters.value.offset = 0;
  load();
}

function toggleSort() {
  filters.value.sort = filters.value.sort === "count_desc" ? "count_asc" : "count_desc";
  applyFilters();
}

function prevPage() {
  if (filters.value.offset! > 0) {
    filters.value.offset = Math.max(0, filters.value.offset! - filters.value.limit!);
    load();
  }
}
function nextPage() {
  if (filters.value.offset! + filters.value.limit! < paging.value.total) {
    filters.value.offset = filters.value.offset! + filters.value.limit!;
    load();
  }
}
const currentPage = computed(() => Math.floor(paging.value.offset / filters.value.limit!) + 1);
const totalPages = computed(() => Math.ceil(paging.value.total / filters.value.limit!) || 1);

// Localização da página do SKU (modo exclusão). Leva a conta filtrada na lista
// pra já abrir mostrando só os anúncios dela. Usada pelo <router-link> do SKU/lápis
// (que dá os gestos nativos: clique normal = mesma guia; botão do meio/direito =
// nova guia) e pelo clique na linha (mesma guia).
function skuRoute(sku: string) {
  const account = filters.value.account;
  return {
    name: "sku-duplicates",
    params: { sku },
    query: account && account !== "all" ? { account } : {},
  };
}
function goSku(sku: string) {
  router.push(skuRoute(sku));
}

// Conta "principal" = a conta ativa do app (a selecionada no seletor de contas).
// Cai pra 'all' se nenhuma estiver ativa/autenticada.
function principalAccount(): string {
  const activeId =
    auth.status.user_id ?? auth.accounts.find((a) => a.is_active)?.user_id;
  return activeId ? String(activeId) : "all";
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}

onMounted(async () => {
  if (auth.accounts.length === 0) await auth.checkAuth();
  // Abre já filtrado na conta principal (ativa), em vez de "Todas".
  filters.value.account = principalAccount();
  await load();
  if (auth.status.authenticated) triggerBgRefresh();
});
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-end justify-between gap-3 mb-1 flex-wrap">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
          Limpeza de catálogo
        </p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Anúncios Repetidos</h2>
      </div>
      <div class="flex items-center gap-3">
        <span
          v-if="autoRefreshing"
          class="inline-flex items-center gap-1.5 text-xs font-medium text-amber-700 bg-amber-50 border border-amber-200 px-2.5 py-1 rounded-full"
          title="Buscando dados novos dos anúncios em segundo plano"
        >
          <Loader2 :size="12" class="animate-spin" />
          Atualizando em segundo plano…
        </span>
        <span v-else-if="snapshot?.inventory_scanned_at" class="text-xs text-gray-400 tabular-nums">
          Inventário: {{ snapshot.inventory_count }} anúncios · atualizado {{ fmtDate(snapshot.inventory_scanned_at) }}
        </span>
        <button
          @click="doRefresh"
          :disabled="autoRefreshing"
          class="px-3.5 py-2 border rounded-xl text-sm font-medium bg-white hover:bg-gray-50 hover:border-gray-300 flex items-center gap-1.5 disabled:opacity-50 transition-colors shadow-sm"
          title="Força a varredura de inventário + vendas agora"
        >
          <RefreshCw :size="14" :class="autoRefreshing ? 'animate-spin' : ''" />
          {{ autoRefreshing ? "Atualizando…" : "Atualizar dados" }}
        </button>
      </div>
    </div>
    <p class="text-sm text-gray-500 mb-5 max-w-2xl">
      Quantos anúncios compartilham o mesmo SKU. Clique no SKU para abrir a página do produto e
      <strong>excluir</strong> os repetidos (foco em enxugar os sem vendas).
    </p>

    <!-- needs refresh (primeira vez, sem snapshot) -->
    <div
      v-if="needsRefresh"
      class="bg-amber-50 border border-amber-200 rounded-2xl p-6 text-center text-amber-800"
    >
      <template v-if="autoRefreshing">
        <p class="font-medium inline-flex items-center gap-2">
          <Loader2 :size="16" class="animate-spin" />
          Montando o primeiro snapshot… pode levar alguns minutos.
        </p>
        <p class="text-amber-700/80 text-xs mt-1">A tela atualiza sozinha quando terminar.</p>
      </template>
      <template v-else>
        <p class="font-medium mb-3">Ainda não há um snapshot de inventário. Rode a primeira varredura.</p>
        <button
          @click="doRefresh"
          class="px-5 py-2.5 bg-meli-blue text-brand-yellow font-semibold rounded-xl text-sm hover:bg-meli-blue-dark inline-flex items-center gap-2 transition-all hover:shadow-md"
        >
          <RefreshCw :size="16" />
          Varrer agora
        </button>
      </template>
    </div>

    <template v-else>
      <!-- Filtros -->
      <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4">
        <div class="flex flex-wrap gap-3 items-end">
          <div class="flex-1 min-w-[220px]">
            <label class="block text-xs text-gray-500 mb-1">Buscar (SKU ou título)</label>
            <div class="relative">
              <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                v-model="filters.q"
                type="text"
                placeholder="SKU ou título…"
                class="w-full pl-9 pr-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                @keyup.enter="applyFilters"
              />
            </div>
          </div>
          <div class="min-w-[150px]">
            <label class="block text-xs text-gray-500 mb-1">Mínimo de repetições</label>
            <input
              v-model.number="filters.min_count"
              type="number" min="2" max="999"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              @keyup.enter="applyFilters"
              @change="applyFilters"
            />
          </div>
          <div v-if="auth.accounts.length > 1" class="min-w-[160px]">
            <label class="block text-xs text-gray-500 mb-1">Conta</label>
            <SelectMenu
              v-model="filters.account"
              :options="[
                { value: 'all', label: 'Todas' },
                ...auth.accounts.map((a) => ({ value: String(a.user_id), label: a.nickname })),
              ]"
              @update:model-value="applyFilters"
            />
          </div>
        </div>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <Loader2 :size="32" class="animate-spin text-meli-blue" />
      </div>

      <!-- Tabela -->
      <div v-else-if="items.length" class="bg-white rounded-2xl border shadow-sm overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b bg-gray-50/80">
                <th class="px-4 py-2.5 font-bold w-10">#</th>
                <th class="px-3 py-2.5 font-bold">SKU</th>
                <th class="px-3 py-2.5 font-bold">Título</th>
                <th class="px-3 py-2.5 font-bold text-right whitespace-nowrap">
                  <button
                    @click="toggleSort"
                    class="inline-flex items-center gap-1 ml-auto hover:text-meli-blue transition-colors text-meli-blue uppercase tracking-wider"
                    :title="filters.sort === 'count_desc' ? 'Mais repetidos primeiro' : 'Menos repetidos primeiro'"
                  >
                    Repetidos
                    <component :is="filters.sort === 'count_desc' ? ArrowDownWideNarrow : ArrowUpNarrowWide" :size="13" />
                  </button>
                </th>
                <th class="px-3 py-2.5 font-bold text-right">Sem vendas</th>
                <th class="px-3 py-2.5 font-bold text-right w-12"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(g, idx) in items"
                :key="g.sku"
                class="border-b last:border-0 hover:bg-brand-yellow-soft/50 dark:hover:bg-zinc-800/60 cursor-pointer transition-colors"
                @click="goSku(g.sku)"
              >
                <td class="px-4 py-2 text-gray-400 tabular-nums">{{ paging.offset + idx + 1 }}.</td>
                <td class="px-3 py-2">
                  <div class="flex items-center gap-2 min-w-0">
                    <img v-if="g.thumbnail" :src="g.thumbnail" class="w-9 h-9 rounded object-cover border flex-shrink-0" />
                    <div v-else class="w-9 h-9 rounded bg-gray-100 flex items-center justify-center flex-shrink-0">
                      <Copy :size="14" class="text-gray-300" />
                    </div>
                    <router-link
                      :to="skuRoute(g.sku)"
                      @click.stop
                      class="font-mono text-xs font-medium text-meli-blue hover:underline"
                    >{{ g.sku }}</router-link>
                  </div>
                </td>
                <td class="px-3 py-2">
                  <div class="truncate max-w-[28rem] text-gray-700" :title="g.title">{{ g.title }}</div>
                </td>
                <td class="px-3 py-2 text-right tabular-nums font-semibold text-gray-800">{{ g.count }}</td>
                <td class="px-3 py-2 text-right tabular-nums">
                  <span :class="g.never_sold_count > 0 ? 'text-red-600 font-medium' : 'text-gray-400'">
                    {{ g.never_sold_count }}
                  </span>
                </td>
                <td class="px-3 py-2 text-right">
                  <router-link
                    :to="skuRoute(g.sku)"
                    @click.stop
                    class="inline-flex p-1.5 rounded-lg hover:bg-gray-100 text-gray-500 hover:text-meli-blue"
                    title="Abrir e excluir repetidos (botão do meio/direito = nova guia)"
                  >
                    <PencilLine :size="16" />
                  </router-link>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-else class="bg-white rounded-2xl border shadow-sm p-10 text-center text-gray-500">
        <Copy :size="32" class="mx-auto mb-3 text-gray-300" />
        Nenhum SKU com {{ filters.min_count }}+ anúncios repetidos.
      </div>

      <!-- Paginação -->
      <div v-if="paging.total > 0" class="flex items-center justify-between mt-4 text-sm text-gray-500">
        <span>{{ paging.total.toLocaleString("pt-BR") }} SKU(s) repetido(s)</span>
        <div class="flex items-center gap-2">
          <button @click="prevPage" :disabled="paging.offset === 0" class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30">
            <ChevronLeft :size="18" />
          </button>
          <span>{{ currentPage }} / {{ totalPages }}</span>
          <button @click="nextPage" :disabled="paging.offset + filters.limit! >= paging.total" class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30">
            <ChevronRight :size="18" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

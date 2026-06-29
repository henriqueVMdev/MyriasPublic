<script setup lang="ts">
import { ref, computed, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  getPerfItems,
  type PerfListResponse,
  type PerfItem,
  type PerfListParams,
} from "@/api/performance";
import { useAuthStore } from "@/stores/auth";
import { useSnapshotAutoRefresh } from "@/composables/useSnapshotAutoRefresh";
import StatusBadge from "@/components/StatusBadge.vue";
import SelectMenu from "@/components/SelectMenu.vue";
import ShippingBadge from "@/components/ShippingBadge.vue";
import PromoPrice from "@/components/PromoPrice.vue";
import ModalityBreakdownChart from "@/components/ModalityBreakdownChart.vue";
import AdsBreakdownChart from "@/components/AdsBreakdownChart.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";
import {
  Search, Loader2, RefreshCw, TrendingUp,
  PackageX, Clock, ChevronLeft, ChevronRight, Tag,
  ChevronDown, ChevronUp, ChevronsUpDown,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

type FilterKind = "all" | "top_sellers" | "no_sales" | "stale";

const resp = ref<PerfListResponse | null>(null);
const loading = ref(false);

// Atualização automática em segundo plano (ao abrir a página / botão manual).
const { autoRefreshing, trigger: triggerBgRefresh } = useSnapshotAutoRefresh({
  account: () => filters.value.account || "all",
  reload: load,
});

const filters = ref<PerfListParams>({
  q: (route.query.q as string) || "",
  filter: "all",
  stale_days: 60,
  status: (route.query.status as string) || "",
  account: "all",
  logistic: "all",
  sort: undefined,
  days: 90,
  offset: 0,
  limit: 50,
});

const LOGISTIC_OPTIONS = [
  { value: "all", label: "Todas" },
  { value: "full", label: "Full" },
  { value: "flex", label: "Flex" },
  { value: "padrao", label: "Padrão" },
];

// Colunas ordenáveis da tabela. Ordem casa com as <td> do corpo.
// Todas vêm do snapshot, então ordenam o escopo inteiro no servidor de graça.
// (visitas/conv saíram desta tela — seguem só no detalhe do item e do SKU.)
const SORT_COLS = [
  { field: "price",     label: "Preço",        desc: "Maior preço",           asc: "Menor preço" },
  { field: "sales",     label: "Vendas",       desc: "Mais vendas",           asc: "Menos vendas",          note: "Total vitalício do anúncio" },
  { field: "visits",    label: "Visitas",      desc: "Mais visitas",          asc: "Menos visitas",         note: "Vitalício (até 2 anos — limite do ML)" },
  { field: "revenue",   label: "Faturamento",  desc: "Maior faturamento",     asc: "Menor faturamento",     note: "Últimos 12 meses (limite da API de pedidos)" },
  { field: "last_sale", label: "Última venda", desc: "Mais antigas primeiro", asc: "Mais recentes primeiro" },
  { field: "created",   label: "Criado em",    desc: "Mais novos primeiro",   asc: "Mais antigos primeiro" },
];

const openSortMenu = ref<string | null>(null);
// Sort efetivo: o backend devolve o aplicado; senão usa o pedido; senão o default.
const activeSort = computed(() => resp.value?.sort || filters.value.sort || "sales_desc");
const activeField = computed(() => activeSort.value.replace(/_(asc|desc)$/, ""));
const activeDir = computed<"asc" | "desc">(() => (activeSort.value.endsWith("_asc") ? "asc" : "desc"));

function sortIcon(field: string) {
  if (activeField.value !== field) return ChevronsUpDown;
  return activeDir.value === "desc" ? ChevronDown : ChevronUp;
}
function toggleSortMenu(field: string) {
  openSortMenu.value = openSortMenu.value === field ? null : field;
}
function setSort(field: string, dir: "asc" | "desc") {
  filters.value.sort = `${field}_${dir}`;
  openSortMenu.value = null;
  applyFilters();
}

const FILTER_TABS: Array<{ value: FilterKind; label: string; icon: any }> = [
  { value: "all", label: "Todos", icon: Tag },
  { value: "top_sellers", label: "Mais vendidos", icon: TrendingUp },
  { value: "no_sales", label: "Sem vendas", icon: PackageX },
  { value: "stale", label: "Parados", icon: Clock },
];

const items = computed<PerfItem[]>(() => resp.value?.items ?? []);
const summary = computed(() => resp.value?.summary);
const byModality = computed(() => resp.value?.by_modality);
const byAds = computed(() => resp.value?.by_ads);
const paging = computed(() => resp.value?.paging ?? { total: 0, offset: 0, limit: 50 });
const needsRefresh = computed(() => resp.value?.needs_refresh ?? false);
const snapshot = computed(() => resp.value?.snapshot);

async function load() {
  loading.value = true;
  try {
    resp.value = await getPerfItems(filters.value);
  } catch (err) {
    console.error("Erro ao carregar performance:", err);
  } finally {
    loading.value = false;
  }
}

function applyFilters() {
  filters.value.offset = 0;
  load();
}

function setFilter(f: FilterKind) {
  filters.value.filter = f;
  applyFilters();
}

// Botão manual "Atualizar dados" = forçar a varredura COMPLETA (inclui inventário).
function doRefresh() {
  if (autoRefreshing.value) return;
  triggerBgRefresh("full");
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

// Navegação nativa: o título do anúncio e o SKU são <router-link> (renderizam
// <a href>), então o navegador dá os gestos — clique normal = mesma guia; botão
// do meio/direito = nova guia. O clique na linha vai pro item na mesma guia.
function itemRoute(it: PerfItem) {
  return `/items/${it.id}`;
}
function skuRoute(sku: string) {
  return `/items/sku/${encodeURIComponent(sku)}`;
}
function goItem(it: PerfItem) {
  router.push(itemRoute(it));
}

// Conta "principal" = a conta ativa do app. Cai pra 'all' se nada estiver ativo.
function principalAccount(): string {
  const activeId =
    auth.status.user_id ?? auth.accounts.find((a) => a.is_active)?.user_id;
  return activeId ? String(activeId) : "all";
}

function fmtPrice(v: number | null): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function staleLabel(it: PerfItem): string {
  if (it.never_sold) return "Nunca vendeu";
  if (it.days_since_last_sale == null) return "—";
  if (it.days_since_last_sale === 0) return "Hoje";
  return `${it.days_since_last_sale}d atrás`;
}

function staleClass(it: PerfItem): string {
  const d = it.never_sold ? Infinity : (it.days_since_last_sale ?? 0);
  if (d >= (filters.value.stale_days ?? 60)) return "text-red-600 font-medium";
  if (d >= 15) return "text-amber-600";
  return "text-gray-600";
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}

// Deep-link do Dashboard (/items?status=paused) ainda funciona como filtro.
watch(
  () => route.query,
  (q) => {
    const s = (q.status as string) || "";
    const qq = (q.q as string) || "";
    if (s !== filters.value.status || qq !== filters.value.q) {
      filters.value.status = s;
      filters.value.q = qq;
      filters.value.offset = 0;
      load();
    }
  }
);

onMounted(async () => {
  if (auth.accounts.length === 0) await auth.checkAuth();
  // Abre já filtrado na conta principal (ativa), em vez de "Todas".
  filters.value.account = principalAccount();
  await load(); // mostra o cache na hora
  // ...e dispara a atualização em segundo plano (sempre que a página abre).
  if (auth.status.authenticated) triggerBgRefresh();
});
</script>

<template>
  <div>
    <!-- Fecha o menu de ordenação ao clicar fora -->
    <div v-if="openSortMenu" class="fixed inset-0 z-10" @click="openSortMenu = null"></div>

    <!-- Header -->
    <div class="flex items-end justify-between gap-3 mb-1 flex-wrap">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
          Análise de anúncios
        </p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Performance dos Anúncios</h2>
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
        <span
          v-if="!autoRefreshing && snapshot?.inventory_scanned_at && !snapshot?.visits_scanned_at"
          class="text-[11px] text-amber-600 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded-full"
          title="As visitas vitalícias são varridas junto da varredura completa (botão Atualizar dados)"
        >
          visitas: varrer
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
      Vendas, faturamento e tempo parado de cada anúncio. Clique pra ver o detalhe (com visitas e conversão) ou o SKU.
      <span class="block text-xs text-gray-400 mt-0.5">Vendas e visitas são vitalícias; faturamento cobre os últimos 12 meses (limite da API de pedidos do ML).</span>
    </p>

    <!-- needs refresh (primeira vez, sem snapshot) -->
    <div
      v-if="needsRefresh"
      class="bg-amber-50 border border-amber-200 rounded-xl p-6 text-center"
    >
      <template v-if="autoRefreshing">
        <p class="text-amber-800 font-medium inline-flex items-center gap-2">
          <Loader2 :size="16" class="animate-spin" />
          Montando o primeiro snapshot… pode levar alguns minutos.
        </p>
        <p class="text-amber-700/80 text-xs mt-1">A tela atualiza sozinha quando terminar.</p>
      </template>
      <template v-else>
        <p class="text-amber-800 font-medium mb-3">
          Ainda não há um snapshot de inventário. Rode a primeira varredura.
        </p>
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
            <label class="block text-xs text-gray-500 mb-1">Buscar (título ou SKU)</label>
            <div class="relative">
              <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                v-model="filters.q"
                type="text"
                placeholder="Título ou SKU…"
                class="w-full pl-9 pr-3 py-2 border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-brand-yellow focus:border-transparent transition-shadow"
                @keyup.enter="applyFilters"
              />
            </div>
          </div>
          <div class="min-w-[150px]">
            <label class="block text-xs text-gray-500 mb-1">Status</label>
            <SelectMenu
              v-model="filters.status"
              :options="[
                { value: 'active', label: 'Ativos' },
                { value: 'paused', label: 'Pausados' },
                { value: 'closed', label: 'Encerrados' },
              ]"
              @update:model-value="applyFilters"
            />
          </div>
          <div class="min-w-[140px]">
            <label class="block text-xs text-gray-500 mb-1">Modalidade (envio)</label>
            <SelectMenu
              v-model="filters.logistic"
              :options="LOGISTIC_OPTIONS"
              @update:model-value="applyFilters"
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

        <!-- Tabs de rankeamento -->
        <div class="flex flex-wrap items-center gap-2 mt-4">
          <button
            v-for="t in FILTER_TABS"
            :key="t.value"
            @click="setFilter(t.value)"
            class="px-3.5 py-1.5 rounded-full text-sm font-medium flex items-center gap-1.5 border transition-all"
            :class="filters.filter === t.value
              ? 'bg-meli-blue text-brand-yellow border-meli-blue shadow-sm'
              : 'border-gray-200 hover:bg-gray-50 hover:border-gray-300 text-gray-600'"
          >
            <component :is="t.icon" :size="14" />
            {{ t.label }}
          </button>
          <div v-if="filters.filter === 'stale'" class="flex items-center gap-1.5 ml-1 text-sm text-gray-600">
            sem vender há
            <input
              v-model.number="filters.stale_days"
              type="number" min="1" max="365"
              class="w-16 px-2 py-1 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-brand-yellow"
              @keyup.enter="applyFilters"
            />
            dias
            <button @click="applyFilters" class="text-meli-blue font-semibold hover:underline text-xs">aplicar</button>
          </div>
        </div>
      </div>

      <!-- Cards de resumo + top -->
      <MetricStrip v-if="summary" :cols="4" class="mb-4">
        <MetricStripItem label="No escopo">
          <div class="text-2xl font-extrabold tabular-nums">{{ summary.total.toLocaleString("pt-BR") }}</div>
        </MetricStripItem>
        <MetricStripItem label="Sem vendas" clickable @click="setFilter('no_sales')">
          <div class="text-2xl font-extrabold text-red-600 tabular-nums">{{ summary.sem_vendas.toLocaleString("pt-BR") }}</div>
        </MetricStripItem>
        <MetricStripItem :label="`Parados +${summary.stale_days}d`" clickable @click="setFilter('stale')">
          <div class="text-2xl font-extrabold text-amber-600 tabular-nums">{{ summary.parados.toLocaleString("pt-BR") }}</div>
        </MetricStripItem>
        <MetricStripItem label="Com vendas" clickable @click="setFilter('top_sellers')">
          <div class="text-2xl font-extrabold text-green-600 tabular-nums">{{ summary.com_vendas.toLocaleString("pt-BR") }}</div>
        </MetricStripItem>
      </MetricStrip>

      <!-- Vendas por modalidade / publicidade -->
      <div v-if="summary" class="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-4">
        <ModalityBreakdownChart :data="byModality" />
        <AdsBreakdownChart :data="byAds" />
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
                <th class="px-4 py-2.5 font-bold">Anúncio</th>
                <th class="px-3 py-2.5 font-bold">SKU</th>
                <th
                  v-for="col in SORT_COLS"
                  :key="col.field"
                  class="px-3 py-2.5 font-bold text-right relative whitespace-nowrap"
                  :title="col.note"
                >
                  <button
                    @click.stop="toggleSortMenu(col.field)"
                    class="inline-flex items-center gap-1 ml-auto hover:text-meli-blue transition-colors uppercase tracking-wider"
                    :class="activeField === col.field ? 'text-meli-blue' : ''"
                  >
                    {{ col.label }}
                    <component :is="sortIcon(col.field)" :size="12" />
                  </button>
                  <div
                    v-if="openSortMenu === col.field"
                    class="absolute right-1 top-full mt-1 z-20 bg-white border rounded-lg shadow-lg py-1 w-48 text-left font-normal normal-case"
                  >
                    <button
                      @click.stop="setSort(col.field, 'desc')"
                      class="w-full px-3 py-1.5 text-xs hover:bg-gray-50 flex items-center gap-2"
                      :class="activeField === col.field && activeDir === 'desc' ? 'text-meli-blue font-medium' : 'text-gray-700'"
                    >
                      <ChevronDown :size="12" /> {{ col.desc }}
                    </button>
                    <button
                      @click.stop="setSort(col.field, 'asc')"
                      class="w-full px-3 py-1.5 text-xs hover:bg-gray-50 flex items-center gap-2"
                      :class="activeField === col.field && activeDir === 'asc' ? 'text-meli-blue font-medium' : 'text-gray-700'"
                    >
                      <ChevronUp :size="12" /> {{ col.asc }}
                    </button>
                  </div>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="it in items"
                :key="it.id"
                class="border-b last:border-0 hover:bg-brand-yellow-soft/50 dark:hover:bg-zinc-800/60 cursor-pointer transition-colors"
                @click="goItem(it)"
              >
                <td class="px-4 py-2.5">
                  <div class="flex items-center gap-2.5 min-w-0">
                    <img v-if="it.thumbnail" :src="it.thumbnail" class="w-9 h-9 rounded-lg object-cover border flex-shrink-0" />
                    <div class="min-w-0">
                      <router-link :to="itemRoute(it)" @click.stop class="block truncate max-w-[22rem] font-medium text-gray-800 hover:underline" :title="it.title">{{ it.title }}</router-link>
                      <div class="flex items-center gap-1.5 mt-0.5">
                        <StatusBadge :status="it.status" />
                        <ShippingBadge :logistic-type="it.logistic_type" />
                        <span v-if="auth.accounts.length > 1" class="text-[10px] text-gray-400">{{ it.nickname }}</span>
                      </div>
                    </div>
                  </div>
                </td>
                <td class="px-3 py-2">
                  <router-link
                    v-if="it.sku"
                    :to="skuRoute(it.sku)"
                    @click.stop
                    class="text-meli-blue hover:underline font-mono text-xs"
                  >{{ it.sku }}</router-link>
                  <span v-else class="text-gray-300">—</span>
                </td>
                <td class="px-3 py-2 text-right tabular-nums">
                  <PromoPrice :price="it.price" :original="it.original_price" />
                </td>
                <td class="px-3 py-2 text-right tabular-nums font-medium" title="Vendas no total (vitalício)">{{ it.sold_quantity ?? 0 }}</td>
                <td class="px-3 py-2 text-right tabular-nums" title="Visitas no total (até 2 anos — limite do ML; da última varredura · só anúncios ativos)">
                  {{ it.visits_lifetime != null ? it.visits_lifetime.toLocaleString("pt-BR") : "—" }}
                </td>
                <td class="px-3 py-2 text-right tabular-nums" title="Faturamento dos últimos 12 meses (limite da API de pedidos). Vendas antigas contam em 'Vendas' mas não aqui.">{{ it.revenue ? fmtPrice(it.revenue) : "—" }}</td>
                <td class="px-3 py-2 text-right tabular-nums whitespace-nowrap" :class="staleClass(it)">
                  {{ staleLabel(it) }}
                </td>
                <td class="px-3 py-2 text-right tabular-nums whitespace-nowrap text-gray-600">
                  {{ fmtDate(it.date_created) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-else class="bg-white rounded-2xl border shadow-sm p-10 text-center text-gray-500">
        <PackageX :size="32" class="mx-auto mb-3 text-gray-300" />
        Nenhum anúncio encontrado nesse filtro.
      </div>

      <!-- Paginação -->
      <div v-if="paging.total > 0" class="flex items-center justify-between mt-4 text-sm text-gray-500">
        <span>{{ paging.total.toLocaleString("pt-BR") }} anúncios</span>
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

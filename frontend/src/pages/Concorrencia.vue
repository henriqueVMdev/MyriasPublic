<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  Swords, Trophy, Loader2, RefreshCw, Search, ExternalLink,
  ChevronLeft, ChevronRight,
} from "lucide-vue-next";
import {
  getCompetitionReport, refreshCompetition,
  type CompetitionFilters, type CompetitionReport,
} from "@/api/competition";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";

const router = useRouter();
const report = ref<CompetitionReport | null>(null);
const loading = ref(false);
const starting = ref(false);
const error = ref("");
const filters = ref<CompetitionFilters>({ q: "", status: "", offset: 0, limit: 50 });
let pollTimer: ReturnType<typeof setTimeout> | null = null;

const summary = computed(() => report.value?.summary);
const paging = computed(() => report.value?.paging ?? { total: 0, offset: 0, limit: 50 });
const currentPage = computed(() => Math.floor(paging.value.offset / paging.value.limit) + 1);
const totalPages = computed(() => Math.max(1, Math.ceil(paging.value.total / paging.value.limit)));
const progress = computed(() => {
  const total = report.value?.total || 0;
  return total ? Math.min(100, Math.round(((report.value?.processed || 0) / total) * 100)) : 0;
});

const STATUS_FILTERS = [
  { value: "", label: "Todos" },
  { value: "competing", label: "Perdendo" },
  { value: "winning", label: "Ganhando" },
  { value: "sharing", label: "Empate" },
  { value: "not_listed", label: "Sem oferta" },
];

const STATUS_META: Record<string, { label: string; cls: string }> = {
  winning: { label: "Ganhando", cls: "text-green-700 bg-green-100 dark:bg-green-900/30 dark:text-green-300" },
  sharing: { label: "Empate", cls: "text-meli-blue bg-blue-100 dark:bg-blue-900/30 dark:text-blue-300" },
  competing: { label: "Perdendo", cls: "text-amber-700 bg-amber-100 dark:bg-amber-900/30 dark:text-amber-300" },
  not_listed: { label: "Sem oferta", cls: "text-gray-600 bg-gray-100 dark:bg-zinc-800 dark:text-gray-300" },
  needs_live_check: { label: "Avulso", cls: "text-gray-500 bg-gray-100 dark:bg-zinc-800 dark:text-gray-400" },
  unknown: { label: "—", cls: "text-gray-400 bg-gray-100 dark:bg-zinc-800" },
};

async function load(silent = false) {
  if (!silent) loading.value = true;
  error.value = "";
  try {
    report.value = await getCompetitionReport(filters.value);
    schedulePoll();
  } catch (err: any) {
    error.value = err?.response?.data?.detail || "Não foi possível carregar a análise.";
  } finally {
    loading.value = false;
  }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  if (report.value?.refreshing || report.value?.status === "running") {
    pollTimer = setTimeout(() => load(true), 3000);
  }
}

async function startRefresh() {
  starting.value = true;
  error.value = "";
  try {
    await refreshCompetition();
    await load(true);
  } catch (err: any) {
    error.value = err?.response?.data?.detail || "Não foi possível iniciar a varredura.";
  } finally {
    starting.value = false;
  }
}

function setStatus(value: string) {
  filters.value.status = value;
  filters.value.offset = 0;
  load();
}
function search() {
  filters.value.offset = 0;
  load();
}
function goPage(delta: number) {
  const next = filters.value.offset! + delta * filters.value.limit!;
  if (next < 0 || next >= paging.value.total) return;
  filters.value.offset = next;
  load();
}

function fmtPrice(v: number | null | undefined): string {
  if (v == null || v === 0) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

onMounted(() => load());
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer); });
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-center gap-3 mb-4 flex-wrap">
      <div class="flex-1 min-w-0">
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight flex items-center gap-2">
          <Swords :size="26" class="text-meli-blue dark:text-brand-yellow" /> Concorrência
        </h2>
        <p class="text-sm text-gray-500">
          Onde você está perdendo o buy box nos anúncios de catálogo.
          <span v-if="report?.scanned_at"> Última varredura: {{ fmtDate(report.scanned_at) }}.</span>
        </p>
      </div>
      <button
        @click="startRefresh"
        :disabled="starting || report?.refreshing"
        class="px-4 py-2 rounded-xl text-sm font-semibold flex items-center gap-2 bg-meli-blue text-brand-yellow hover:opacity-90 disabled:opacity-50"
      >
        <RefreshCw :size="16" :class="(starting || report?.refreshing) ? 'animate-spin' : ''" />
        {{ report?.refreshing ? "Varrendo…" : "Atualizar" }}
      </button>
    </div>

    <div v-if="error" class="mb-4 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">{{ error }}</div>

    <!-- Progresso da varredura -->
    <div v-if="report?.refreshing || report?.status === 'running'" class="mb-4 bg-white dark:bg-brand-black-soft rounded-xl border dark:border-zinc-800 p-4">
      <div class="flex items-center justify-between text-sm mb-2">
        <span class="text-gray-600 dark:text-gray-300">Analisando anúncios… {{ report?.processed }}/{{ report?.total }}</span>
        <span class="text-gray-400">{{ progress }}%</span>
      </div>
      <div class="h-2 rounded-full bg-gray-100 dark:bg-zinc-800 overflow-hidden">
        <div class="h-full bg-meli-blue dark:bg-brand-yellow transition-all" :style="{ width: progress + '%' }"></div>
      </div>
    </div>

    <div v-if="loading && !report" class="flex items-center justify-center py-16">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <!-- Sem snapshot -->
    <div v-else-if="report && !report.has_snapshot" class="text-center py-16 bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800">
      <Swords :size="40" class="mx-auto text-gray-300 mb-3" />
      <p class="text-gray-600 dark:text-gray-300 font-medium">Nenhuma varredura de concorrência ainda.</p>
      <p class="text-sm text-gray-400 mb-4">Rode a primeira análise para ver onde está perdendo o buy box.</p>
      <button @click="startRefresh" :disabled="starting" class="px-4 py-2 rounded-xl text-sm font-semibold bg-meli-blue text-brand-yellow disabled:opacity-50">
        Analisar agora
      </button>
    </div>

    <template v-else-if="report">
      <!-- Resumo -->
      <MetricStrip :cols="6" class="mb-4">
        <MetricStripItem label="Catálogo"><div class="text-2xl font-bold">{{ summary?.catalog ?? 0 }}</div></MetricStripItem>
        <MetricStripItem label="Perdendo"><div class="text-2xl font-bold text-amber-600">{{ summary?.competing ?? 0 }}</div></MetricStripItem>
        <MetricStripItem label="Ganhando"><div class="text-2xl font-bold text-green-600">{{ summary?.winning ?? 0 }}</div></MetricStripItem>
        <MetricStripItem label="Empate"><div class="text-2xl font-bold text-meli-blue">{{ summary?.sharing ?? 0 }}</div></MetricStripItem>
        <MetricStripItem label="Sem oferta"><div class="text-2xl font-bold text-gray-500">{{ summary?.not_listed ?? 0 }}</div></MetricStripItem>
        <MetricStripItem label="Avulsos"><div class="text-2xl font-bold text-gray-400">{{ summary?.standalone ?? 0 }}</div></MetricStripItem>
      </MetricStrip>

      <!-- Filtros -->
      <div class="flex items-center gap-2 mb-3 flex-wrap">
        <button
          v-for="f in STATUS_FILTERS" :key="f.value"
          @click="setStatus(f.value)"
          class="px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
          :class="filters.status === f.value ? 'bg-meli-blue text-brand-yellow' : 'bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-gray-300 hover:bg-gray-200'"
        >{{ f.label }}</button>
        <div class="relative ml-auto">
          <Search :size="15" class="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            v-model="filters.q" @keyup.enter="search"
            placeholder="Buscar título/SKU…"
            class="pl-8 pr-3 py-1.5 rounded-lg border dark:border-zinc-700 dark:bg-zinc-800 text-sm w-56"
          />
        </div>
      </div>

      <!-- Tabela -->
      <div class="bg-white dark:bg-brand-black-soft rounded-xl border dark:border-zinc-800 shadow-sm overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-gray-500 dark:text-gray-400 border-b dark:border-zinc-800">
                <th class="py-2.5 px-4 font-medium">Anúncio</th>
                <th class="py-2.5 px-3 font-medium">Status</th>
                <th class="py-2.5 px-3 font-medium text-right">Seu preço</th>
                <th class="py-2.5 px-3 font-medium text-right">Vencedor</th>
                <th class="py-2.5 px-3 font-medium text-right">Diferença</th>
                <th class="py-2.5 px-3 font-medium text-right">P/ ganhar</th>
                <th class="py-2.5 px-3 font-medium text-center">Pos.</th>
                <th class="py-2.5 px-3 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="it in report.items" :key="it.id"
                class="border-b last:border-0 dark:border-zinc-800 hover:bg-gray-50 dark:hover:bg-zinc-800/40 cursor-pointer"
                @click="router.push(`/items/${it.id}`)"
              >
                <td class="py-2 px-4">
                  <div class="flex items-center gap-2 min-w-0">
                    <img v-if="it.thumbnail" :src="it.thumbnail" alt="" class="w-9 h-9 rounded object-cover shrink-0" />
                    <div class="min-w-0">
                      <div class="truncate max-w-xs">{{ it.title }}</div>
                      <div class="text-[11px] text-gray-400 font-mono">{{ it.sku || it.id }}</div>
                    </div>
                  </div>
                </td>
                <td class="py-2 px-3">
                  <span class="px-2 py-0.5 rounded text-[11px] font-semibold" :class="STATUS_META[it.comp_status]?.cls">
                    <Trophy v-if="it.comp_status === 'winning'" :size="11" class="inline -mt-0.5" />
                    {{ STATUS_META[it.comp_status]?.label ?? it.comp_status }}
                  </span>
                </td>
                <td class="py-2 px-3 text-right tabular-nums font-semibold">{{ fmtPrice(it.price) }}</td>
                <td class="py-2 px-3 text-right tabular-nums text-gray-500">{{ fmtPrice(it.winner_price) }}</td>
                <td class="py-2 px-3 text-right tabular-nums" :class="(it.price_gap ?? 0) > 0 ? 'text-red-600' : 'text-green-600'">
                  <template v-if="it.price_gap != null">
                    {{ fmtPrice(it.price_gap) }}
                    <span v-if="it.price_gap_pct != null" class="text-[11px] text-gray-400">({{ it.price_gap_pct }}%)</span>
                  </template>
                  <template v-else>—</template>
                </td>
                <td class="py-2 px-3 text-right tabular-nums text-meli-blue dark:text-brand-yellow">{{ fmtPrice(it.price_to_win) }}</td>
                <td class="py-2 px-3 text-center tabular-nums text-gray-500">{{ it.my_position || "—" }}</td>
                <td class="py-2 px-3 text-right" @click.stop>
                  <a v-if="it.permalink" :href="it.permalink" target="_blank" class="text-gray-400 hover:text-meli-blue inline-flex" title="Ver no ML">
                    <ExternalLink :size="14" />
                  </a>
                </td>
              </tr>
              <tr v-if="report.items.length === 0">
                <td colspan="8" class="py-10 text-center text-gray-400 text-sm">Nenhum anúncio para este filtro.</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Paginação -->
        <div v-if="paging.total > paging.limit" class="flex items-center justify-between px-4 py-2.5 border-t dark:border-zinc-800 text-sm">
          <span class="text-gray-500">{{ paging.total }} anúncios</span>
          <div class="flex items-center gap-2">
            <button @click="goPage(-1)" :disabled="currentPage <= 1" class="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-zinc-800 disabled:opacity-40"><ChevronLeft :size="16" /></button>
            <span class="text-gray-500">{{ currentPage }} / {{ totalPages }}</span>
            <button @click="goPage(1)" :disabled="currentPage >= totalPages" class="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-zinc-800 disabled:opacity-40"><ChevronRight :size="16" /></button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Image,
  Loader2,
  RefreshCw,
  Search,
  Wrench,
} from "lucide-vue-next";
import {
  getQualityReport,
  refreshQuality,
  refreshQualityItem,
  type QualityFilters,
  type QualityIssue,
  type QualityResponse,
} from "@/api/quality";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";
import SelectMenu from "@/components/SelectMenu.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import QualityFixModal from "@/components/QualityFixModal.vue";

const report = ref<QualityResponse | null>(null);
const loading = ref(false);
const starting = ref(false);
const rechecking = ref<Set<string>>(new Set());
const error = ref("");
const filters = ref<QualityFilters>({ q: "", issue: "", status: "", offset: 0, limit: 50 });
let pollTimer: ReturnType<typeof setTimeout> | null = null;

const fixSku = ref<string | null>(null);

function onFixSaved(itemIds: string[]) {
  for (const id of itemIds) {
    const row = report.value?.items.find((item) => item.id === id);
    if (row) row.validation_status = "validating";
  }
  if (report.value && itemIds.length) {
    report.value.validating_count = Math.max(1, report.value.validating_count);
  }
  schedulePoll();
}

const summary = computed(() => report.value?.summary);
const paging = computed(() => report.value?.paging ?? { total: 0, offset: 0, limit: 50 });
const currentPage = computed(() => Math.floor(paging.value.offset / paging.value.limit) + 1);
const totalPages = computed(() => Math.max(1, Math.ceil(paging.value.total / paging.value.limit)));
const progress = computed(() => {
  const total = report.value?.total || 0;
  return total ? Math.min(100, Math.round(((report.value?.processed || 0) / total) * 100)) : 0;
});

const topAttributeIssues = computed(() =>
  (summary.value?.issues || []).filter((issue) => issue.type === "attribute").slice(0, 8)
);

const issueGroups = computed(() => {
  const groups = [
    {
      label: "Gerais",
      options: [
        { value: "type:attribute", label: "Qualquer atributo incompleto" },
        { value: "clip", label: "Sem clipe" },
        { value: "description", label: "Sem descrição" },
        { value: "pictures", label: "Fotos a melhorar" },
      ],
    },
  ];
  if (topAttributeIssues.value.length) {
    groups.push({
      label: "Atributos",
      options: topAttributeIssues.value.map((issue) => ({ value: issue.key, label: issue.label })),
    });
  }
  return groups;
});

async function load(silent = false) {
  if (!silent) loading.value = true;
  error.value = "";
  try {
    report.value = await getQualityReport(filters.value);
    schedulePoll();
  } catch (err: any) {
    error.value = err?.response?.data?.detail || "Não foi possível carregar a auditoria.";
  } finally {
    loading.value = false;
  }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  if (
    report.value?.refreshing ||
    report.value?.status === "running" ||
    (report.value?.validating_count || 0) > 0
  ) {
    pollTimer = setTimeout(() => load(true), 3000);
  }
}

async function recheckItem(itemId: string) {
  if (rechecking.value.has(itemId)) return;
  rechecking.value = new Set(rechecking.value).add(itemId);
  error.value = "";
  try {
    await refreshQualityItem(itemId);
    const item = report.value?.items.find((row) => row.id === itemId);
    if (item) item.validation_status = "validating";
    if (report.value) report.value.validating_count = Math.max(1, report.value.validating_count);
    schedulePoll();
  } catch (err: any) {
    error.value = err?.response?.data?.detail || "Não foi possível verificar este anúncio.";
  } finally {
    const next = new Set(rechecking.value);
    next.delete(itemId);
    rechecking.value = next;
  }
}

async function startAudit() {
  if (starting.value || report.value?.refreshing) return;
  starting.value = true;
  error.value = "";
  try {
    await refreshQuality();
    await load(true);
  } catch (err: any) {
    error.value = err?.response?.data?.detail || "Não foi possível iniciar a auditoria.";
  } finally {
    starting.value = false;
  }
}

function applyFilters() {
  filters.value.offset = 0;
  load();
}

function selectIssue(key: string) {
  filters.value.issue = filters.value.issue === key ? "" : key;
  applyFilters();
}

function previousPage() {
  if ((filters.value.offset || 0) <= 0) return;
  filters.value.offset = Math.max(0, (filters.value.offset || 0) - (filters.value.limit || 50));
  load();
}

function nextPage() {
  if ((filters.value.offset || 0) + (filters.value.limit || 50) >= paging.value.total) return;
  filters.value.offset = (filters.value.offset || 0) + (filters.value.limit || 50);
  load();
}

function formatDate(value?: string | null): string {
  if (!value) return "";
  return new Date(value).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

function issueClass(issue: QualityIssue): string {
  if (issue.type === "attribute") return "bg-amber-50 text-amber-800 border-amber-200 dark:bg-amber-950/30 dark:text-amber-300";
  if (issue.type === "description") return "bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/30 dark:text-blue-300";
  if (issue.type === "clip") return "bg-violet-50 text-violet-700 border-violet-200 dark:bg-violet-950/30 dark:text-violet-300";
  return "bg-gray-50 text-gray-700 border-gray-200";
}

onMounted(async () => {
  await load();
});

onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer);
});
</script>

<template>
  <div class="space-y-4">
    <!-- Header -->
    <div class="flex items-end justify-between gap-3 flex-wrap">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
          Qualidade do catálogo
        </p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Anúncios Incompletos</h2>
      </div>
      <div class="flex items-center gap-3">
        <span
          v-if="report?.refreshing || report?.status === 'running'"
          class="inline-flex items-center gap-1.5 text-xs font-medium text-amber-700 bg-amber-50 border border-amber-200 px-2.5 py-1 rounded-full"
          title="Auditoria rodando em segundo plano"
        >
          <Loader2 :size="12" class="animate-spin" />
          Atualizando em segundo plano…
        </span>
        <span v-else-if="report?.scanned_at" class="text-xs text-gray-400 tabular-nums">
          Atualizado {{ formatDate(report.scanned_at) }}
        </span>
        <button
          @click="startAudit"
          :disabled="starting || report?.refreshing"
          class="px-3.5 py-2 border rounded-xl text-sm font-medium bg-white hover:bg-gray-50 hover:border-gray-300 flex items-center gap-1.5 disabled:opacity-50 transition-colors shadow-sm"
          title="Roda a auditoria completa agora (também roda sozinha às 03:00)"
        >
          <RefreshCw :size="14" :class="{ 'animate-spin': starting || report?.refreshing }" />
          {{ report?.refreshing ? "Na fila…" : "Atualizar dados" }}
        </button>
      </div>
    </div>
    <p class="text-sm text-gray-500 max-w-2xl -mt-2">
      Encontre o que falta preencher e priorize os ajustes que aumentam a exposição dos seus anúncios.
    </p>

    <div v-if="error" class="flex items-center gap-2 px-4 py-3 rounded-xl border border-red-200 bg-red-50 text-red-700 text-sm">
      <AlertTriangle :size="17" /> {{ error }}
    </div>

    <div v-for="warning in report?.warnings || []" :key="warning" class="flex items-center gap-2 px-4 py-3 rounded-xl border border-amber-200 bg-amber-50 text-amber-800 text-sm">
      <AlertTriangle :size="17" class="flex-shrink-0" /> {{ warning }}
    </div>

    <!-- A auditoria grande continua em background e entrega checkpoints. -->
    <div v-if="report?.refreshing || report?.status === 'running'" class="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
      <div class="flex items-center justify-between gap-3 text-amber-800">
        <p class="text-sm font-medium inline-flex items-center gap-2">
          <Loader2 :size="15" class="animate-spin text-amber-600" />
          Auditoria em andamento — você pode continuar usando o app normalmente.
        </p>
        <p class="text-xs tabular-nums text-amber-700/80 whitespace-nowrap">
          {{ report.processed.toLocaleString("pt-BR") }} de {{ report.total.toLocaleString("pt-BR") }} · {{ progress }}%
        </p>
      </div>
      <div class="mt-2 h-1.5 rounded-full bg-amber-200/60 overflow-hidden">
        <div class="h-full bg-amber-500 rounded-full transition-all duration-500" :style="{ width: `${progress}%` }"></div>
      </div>
    </div>

    <div
      v-else-if="report?.validating_count"
      class="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800 flex items-center gap-3"
    >
      <Loader2 :size="17" class="animate-spin flex-shrink-0 text-amber-600" />
      <div>
        <p class="text-sm font-bold">Confirmando {{ report.validating_count }} anúncio{{ report.validating_count === 1 ? '' : 's' }}</p>
        <p class="text-xs text-amber-700/80">O resultado será atualizado automaticamente quando o Mercado Livre processar as alterações.</p>
      </div>
    </div>

    <!-- Resumo -->
    <MetricStrip v-if="summary" :cols="5">
      <MetricStripItem clickable @click="selectIssue('type:attribute')">
        <template #label>
          <span :class="filters.issue === 'type:attribute' ? 'text-gray-900 underline decoration-brand-yellow decoration-2 underline-offset-4' : ''">Ficha técnica</span>
        </template>
        <div class="text-2xl font-extrabold tabular-nums">{{ summary.attributes.toLocaleString("pt-BR") }}</div>
        <p class="text-[11px] text-gray-400 mt-0.5">atributos incompletos</p>
      </MetricStripItem>
      <MetricStripItem clickable @click="selectIssue('clip')">
        <template #label>
          <span :class="filters.issue === 'clip' ? 'text-gray-900 underline decoration-brand-yellow decoration-2 underline-offset-4' : ''">Clipe</span>
        </template>
        <div class="text-2xl font-extrabold tabular-nums">{{ summary.clip.toLocaleString("pt-BR") }}</div>
        <p class="text-[11px] text-gray-400 mt-0.5">sem clipe recomendado</p>
      </MetricStripItem>
      <MetricStripItem clickable @click="selectIssue('description')">
        <template #label>
          <span :class="filters.issue === 'description' ? 'text-gray-900 underline decoration-brand-yellow decoration-2 underline-offset-4' : ''">Descrição</span>
        </template>
        <div class="text-2xl font-extrabold tabular-nums">{{ summary.description.toLocaleString("pt-BR") }}</div>
        <p class="text-[11px] text-gray-400 mt-0.5">sem descrição</p>
      </MetricStripItem>
      <MetricStripItem clickable @click="selectIssue('pictures')">
        <template #label>
          <span :class="filters.issue === 'pictures' ? 'text-gray-900 underline decoration-brand-yellow decoration-2 underline-offset-4' : ''">Imagens</span>
        </template>
        <div class="text-2xl font-extrabold tabular-nums">{{ summary.pictures.toLocaleString("pt-BR") }}</div>
        <p class="text-[11px] text-gray-400 mt-0.5">fotos a melhorar</p>
      </MetricStripItem>
      <MetricStripItem label="Completos">
        <div class="text-2xl font-extrabold tabular-nums">{{ summary.complete.toLocaleString("pt-BR") }}</div>
        <p class="text-[11px] text-gray-400 mt-0.5">sem pendências</p>
      </MetricStripItem>
    </MetricStrip>

    <!-- Filtros -->
    <div class="bg-white rounded-2xl border shadow-sm p-4">
      <div class="flex flex-wrap gap-3 items-end">
        <div class="flex-1 min-w-[220px]">
          <label class="block text-xs text-gray-500 mb-1">Buscar (título, SKU ou MLB)</label>
          <div class="relative">
            <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              v-model="filters.q"
              type="text"
              placeholder="Título, SKU ou MLB…"
              class="w-full pl-9 pr-3 py-2 border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-brand-yellow focus:border-transparent transition-shadow"
              @keyup.enter="applyFilters"
            />
          </div>
        </div>
        <div class="min-w-[220px]">
          <label class="block text-xs text-gray-500 mb-1">Problema</label>
          <SelectMenu
            v-model="filters.issue"
            :groups="issueGroups"
            empty-label="Todos os problemas"
            @update:model-value="applyFilters"
          />
        </div>
        <div class="min-w-[160px]">
          <label class="block text-xs text-gray-500 mb-1">Status</label>
          <SelectMenu
            v-model="filters.status"
            :options="[
              { value: 'active', label: 'Ativos' },
              { value: 'paused', label: 'Pausados' },
            ]"
            empty-label="Ativos e pausados"
            @update:model-value="applyFilters"
          />
        </div>
      </div>

      <!-- Atributos que mais faltam -->
      <div v-if="topAttributeIssues.length" class="flex flex-wrap items-center gap-2 mt-4">
        <button
          v-for="issue in topAttributeIssues"
          :key="issue.key"
          @click="selectIssue(issue.key)"
          class="px-3.5 py-1.5 rounded-full text-sm font-medium flex items-center gap-1.5 border transition-all"
          :class="filters.issue === issue.key
            ? 'bg-meli-blue text-brand-yellow border-meli-blue shadow-sm'
            : 'border-gray-200 hover:bg-gray-50 hover:border-gray-300 text-gray-600'"
        >
          {{ issue.label }}
          <span class="font-bold tabular-nums">{{ issue.count.toLocaleString("pt-BR") }}</span>
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading && !report" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <!-- Primeira execução -->
    <div v-else-if="!report?.has_snapshot && !report?.refreshing" class="bg-white rounded-2xl border shadow-sm p-10 text-center">
      <Wrench :size="32" class="mx-auto text-gray-300 mb-3" />
      <h3 class="font-bold">A análise ainda não foi executada</h3>
      <p class="text-sm text-gray-500 mt-1">A auditoria roda diariamente às 03:00. Você também pode iniciá-la agora.</p>
      <button
        @click="startAudit"
        class="mt-4 px-5 py-2.5 bg-meli-blue text-brand-yellow font-semibold rounded-xl text-sm hover:bg-meli-blue-dark inline-flex items-center gap-2 transition-all hover:shadow-md"
      >
        <RefreshCw :size="16" /> Iniciar auditoria
      </button>
    </div>

    <div v-else-if="report?.items.length === 0" class="bg-white rounded-2xl border shadow-sm p-10 text-center text-gray-500">
      <CheckCircle2 :size="32" class="mx-auto mb-3 text-green-500" />
      Nenhum anúncio encontrado. Tente remover os filtros ou aguarde a auditoria terminar.
    </div>

    <!-- Tabela -->
    <div v-else class="bg-white rounded-2xl border shadow-sm overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b bg-gray-50/80">
              <th class="px-4 py-2.5 font-bold">Anúncio</th>
              <th class="px-3 py-2.5 font-bold">Qualidade</th>
              <th class="px-3 py-2.5 font-bold">O que falta</th>
              <th class="px-3 py-2.5 font-bold text-right">Ação</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in report?.items || []"
              :key="item.id"
              class="border-b last:border-0 hover:bg-brand-yellow-soft/50 dark:hover:bg-zinc-800/60 transition-colors"
            >
              <td class="px-4 py-2.5 min-w-[300px]">
                <div class="flex items-center gap-2.5 min-w-0">
                  <img v-if="item.thumbnail" :src="item.thumbnail" class="w-9 h-9 rounded-lg object-cover border flex-shrink-0" :alt="item.title" />
                  <div v-else class="w-9 h-9 rounded-lg border bg-gray-50 flex items-center justify-center flex-shrink-0"><Image :size="15" class="text-gray-300" /></div>
                  <div class="min-w-0">
                    <p class="truncate max-w-[22rem] font-medium text-gray-800" :title="item.title">{{ item.title }}</p>
                    <div class="flex items-center gap-1.5 mt-0.5">
                      <StatusBadge :status="item.status" />
                      <span class="text-[10px] text-gray-400 font-mono">{{ item.id }}<span v-if="item.sku"> · {{ item.sku }}</span></span>
                    </div>
                  </div>
                </div>
              </td>
              <td class="px-3 py-2 whitespace-nowrap">
                <template v-if="item.score != null">
                  <p class="font-extrabold tabular-nums">{{ Math.round(item.score) }}%</p>
                  <p class="text-[11px] text-gray-400">{{ item.level }}</p>
                </template>
                <span v-else class="text-gray-300">—</span>
              </td>
              <td class="px-3 py-2 min-w-[320px]">
                <span
                  v-if="item.validation_status === 'validating'"
                  class="mb-1.5 inline-flex items-center gap-1.5 rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-[11px] font-bold text-blue-700"
                >
                  <Loader2 :size="12" class="animate-spin" /> Validando alterações
                </span>
                <div class="flex flex-wrap gap-1.5">
                  <span v-for="issue in item.issues.slice(0, 5)" :key="issue.key" class="px-2 py-0.5 rounded-full border text-[11px] font-semibold" :class="issueClass(issue)">{{ issue.label }}</span>
                  <span v-if="item.issues.length > 5" class="px-2 py-0.5 rounded-full border text-[11px] font-semibold bg-gray-50 text-gray-500">+{{ item.issues.length - 5 }}</span>
                </div>
              </td>
              <td class="px-3 py-2 text-right whitespace-nowrap">
                <div class="inline-flex flex-col items-stretch gap-1.5">
                  <button
                    v-if="item.sku"
                    class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg bg-meli-blue text-brand-yellow text-xs font-semibold hover:bg-meli-blue-dark transition-colors"
                    @click="fixSku = item.sku"
                  >
                    <Wrench :size="13" /> Corrigir
                  </button>
                  <router-link
                    v-if="item.sku"
                    :to="`/bulk/sku/${encodeURIComponent(item.sku)}`"
                    class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-semibold text-gray-600 hover:bg-gray-50 hover:border-gray-300 transition-colors"
                  >
                    Edição completa
                  </router-link>
                  <a
                    v-else-if="item.permalink"
                    :href="item.permalink"
                    target="_blank"
                    rel="noopener"
                    class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-semibold text-gray-600 hover:bg-gray-50 hover:border-gray-300 transition-colors"
                  >
                    Abrir <ExternalLink :size="13" />
                  </a>
                  <button
                    class="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-semibold text-gray-600 hover:bg-gray-50 hover:border-gray-300 transition-colors disabled:opacity-50"
                    :disabled="item.validation_status === 'validating' || rechecking.has(item.id)"
                    @click="recheckItem(item.id)"
                  >
                    <RefreshCw :size="13" :class="{ 'animate-spin': item.validation_status === 'validating' || rechecking.has(item.id) }" />
                    {{ item.validation_status === "validating" ? "Validando" : "Verificar novamente" }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Paginação -->
    <div v-if="paging.total > 0" class="flex items-center justify-between text-sm text-gray-500">
      <span>
        {{ paging.total.toLocaleString("pt-BR") }} anúncios para corrigir
        <template v-if="summary"> · {{ summary.complete.toLocaleString("pt-BR") }} completos</template>
      </span>
      <div class="flex items-center gap-2">
        <button @click="previousPage" :disabled="currentPage <= 1" class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30">
          <ChevronLeft :size="18" />
        </button>
        <span>{{ currentPage }} / {{ totalPages }}</span>
        <button @click="nextPage" :disabled="currentPage >= totalPages" class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30">
          <ChevronRight :size="18" />
        </button>
      </div>
    </div>

    <QualityFixModal
      v-if="fixSku"
      :sku="fixSku"
      @close="fixSku = null"
      @saved="onFixSaved"
    />
  </div>
</template>

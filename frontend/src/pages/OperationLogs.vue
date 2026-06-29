<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import {
  getOperations,
  type OperationGroup,
  type OperationFilters,
} from "@/api/logs";

// `actor` vem da página-mãe (Histórico) que tem o filtro por usuário.
const props = defineProps<{ actor?: string }>();
import { useAuthStore } from "@/stores/auth";
import {
  operationLabel,
  operationSummary,
  operationChanges,
  affectedAds,
  listingTypeLabel,
} from "@/lib/opHistory";
import SelectMenu from "@/components/SelectMenu.vue";
import {
  Filter,
  Loader2,
  ChevronLeft,
  ChevronRight,
  CheckCircle,
  XCircle,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
  ArrowRight,
  User,
} from "lucide-vue-next";

const router = useRouter();
const auth = useAuthStore();

const operations = ref<OperationGroup[]>([]);
const paging = ref({ total: 0, offset: 0, limit: 50 });
const loading = ref(false);
const expandedKey = ref<string | null>(null);

const filters = ref<OperationFilters>({
  operation_type: "",
  status: "",
  date_from: "",
  date_to: "",
  offset: 0,
  limit: 50,
});

function buildParams(): OperationFilters {
  const p: OperationFilters = { ...filters.value };
  // date_to inclusivo até o fim do dia
  if (p.date_to) p.date_to = `${p.date_to}T23:59:59`;
  if (props.actor) p.actor = props.actor;
  return p;
}

async function load() {
  loading.value = true;
  try {
    const resp = await getOperations(buildParams());
    operations.value = resp.operations;
    paging.value = resp.paging;
  } finally {
    loading.value = false;
  }
}

function applyFilters() {
  filters.value.offset = 0;
  load();
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

onMounted(load);

// Recarrega quando o filtro de usuário (vindo da página-mãe) muda.
watch(() => props.actor, () => { filters.value.offset = 0; load(); });

// ─── Helpers de exibição ──────────────────────────────────────────────────────
function toggle(key: string) {
  expandedKey.value = expandedKey.value === key ? null : key;
}

function nickname(userId: number | null | undefined): string {
  if (!userId) return "";
  const acc = auth.accounts.find((a) => a.user_id === userId);
  return acc?.nickname || `Conta ${userId}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR", {
    day: "2-digit", month: "2-digit", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

const statusIcon = {
  success: { icon: CheckCircle, color: "text-green-500" },
  error: { icon: XCircle, color: "text-red-500" },
  partial: { icon: AlertTriangle, color: "text-yellow-500" },
} as const;
function sIcon(status: string) {
  return statusIcon[status as keyof typeof statusIcon] || statusIcon.error;
}

function openDetail(key: string) {
  router.push({ name: "operation-detail", params: { key } });
}

// memoiza derivações por operação pra não recalcular no template
const view = computed(() =>
  operations.value.map((op) => ({
    op,
    label: operationLabel(op),
    summary: operationSummary(op),
    changes: operationChanges(op),
    ads: affectedAds(op),
  }))
);
</script>

<template>
  <div>
    <!-- Filtros -->
    <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4">
      <div class="flex flex-wrap gap-3 items-end">
        <div class="min-w-[180px]">
          <label class="block text-xs text-gray-500 mb-1">Tipo</label>
          <SelectMenu
            v-model="filters.operation_type"
            :options="[
              { value: 'bulk_update_multi_account', label: 'Edição por SKU' },
              { value: 'clone', label: 'Cópia de anúncio' },
              { value: 'promotion_add', label: 'Enviado para promoção' },
              { value: 'promotion_remove', label: 'Removido de promoção' },
              { value: 'delete_listing', label: 'Exclusão de repetidos' },
            ]"
          />
        </div>
        <div class="min-w-[140px]">
          <label class="block text-xs text-gray-500 mb-1">Status</label>
          <SelectMenu
            v-model="filters.status"
            :options="[
              { value: 'success', label: 'Sucesso' },
              { value: 'error', label: 'Erro' },
              { value: 'partial', label: 'Parcial' },
            ]"
          />
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">De</label>
          <input
            v-model="filters.date_from"
            type="date"
            class="px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
          />
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">Até</label>
          <input
            v-model="filters.date_to"
            type="date"
            class="px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
          />
        </div>
        <button
          @click="applyFilters"
          class="px-4 py-2 bg-meli-blue text-brand-yellow rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-2"
        >
          <Filter :size="16" /> Filtrar
        </button>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <div v-else-if="view.length" class="bg-white rounded-2xl border shadow-sm overflow-hidden">
      <div class="divide-y">
        <div v-for="row in view" :key="row.op.key">
          <!-- Card header -->
          <div
            class="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-gray-50 transition-colors"
            @click="toggle(row.op.key)"
          >
            <component
              :is="sIcon(row.op.status).icon"
              :size="20"
              :class="sIcon(row.op.status).color"
              class="flex-shrink-0"
            />
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-semibold text-gray-900">{{ row.label }}</span>
                <span class="text-xs text-gray-400">{{ formatDate(row.op.created_at) }}</span>
                <span
                  v-if="row.op.actor"
                  class="text-[11px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-600"
                  title="Quem executou"
                >
                  <User :size="11" /> {{ row.op.actor }}
                </span>
              </div>
              <p class="text-sm text-gray-500 truncate">{{ row.summary }}</p>
            </div>
            <!-- Stats -->
            <div class="flex items-center gap-3 flex-shrink-0 text-xs">
              <span class="text-gray-500">{{ row.op.success }}/{{ row.op.total }} ok</span>
              <span v-if="row.op.error" class="text-red-500 font-medium">
                {{ row.op.error }} erro{{ row.op.error !== 1 ? 's' : '' }}
              </span>
            </div>
            <component
              :is="expandedKey === row.op.key ? ChevronUp : ChevronDown"
              :size="16"
              class="text-gray-400 flex-shrink-0"
            />
          </div>

          <!-- Expanded -->
          <div v-if="expandedKey === row.op.key" class="bg-gray-50 border-t px-4 py-3 space-y-3">
            <!-- O que mudou -->
            <div v-if="row.changes.length">
              <h4 class="text-xs font-semibold text-gray-500 mb-1.5 uppercase">O que mudou</h4>
              <div class="flex gap-1.5 flex-wrap">
                <span
                  v-for="c in row.changes"
                  :key="c"
                  class="text-[11px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-700"
                >{{ c }}</span>
              </div>
            </div>

            <!-- Anúncios afetados -->
            <div>
              <h4 class="text-xs font-semibold text-gray-500 mb-1.5 uppercase">
                Anúncios ({{ row.ads.length }})
              </h4>
              <div class="space-y-1 max-h-72 overflow-auto">
                <div
                  v-for="(ad, i) in row.ads.slice(0, 50)"
                  :key="(ad.mlb || '') + i"
                  class="bg-white px-2.5 py-1.5 rounded border text-xs"
                >
                  <div class="flex items-center gap-2">
                    <component
                      :is="sIcon(ad.status).icon"
                      :size="13"
                      :class="sIcon(ad.status).color"
                      class="flex-shrink-0"
                    />
                    <span v-if="ad.sku" class="font-mono font-medium text-gray-700 flex-shrink-0">{{ ad.sku }}</span>
                    <span class="text-gray-600 truncate flex-1">{{ ad.title || ad.mlb || "—" }}</span>
                    <span
                      v-if="ad.actionLabel"
                      class="text-[10px] px-1.5 py-0.5 rounded font-medium flex-shrink-0"
                      :class="ad.status === 'error' ? 'bg-red-50 text-red-600' : 'bg-gray-800 text-white'"
                    >{{ ad.actionLabel }}</span>
                    <span
                      v-if="ad.listingType"
                      class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600 flex-shrink-0"
                    >{{ listingTypeLabel(ad.listingType) }}</span>
                    <span v-if="ad.userId" class="text-gray-400 flex-shrink-0">{{ nickname(ad.userId) }}</span>
                  </div>
                  <!-- Mudanças campo a campo: "Status: Pausado → Ativo" -->
                  <div v-if="ad.changes && ad.changes.length" class="flex flex-wrap gap-1 mt-1 pl-5">
                    <span
                      v-for="ch in ad.changes"
                      :key="ch.field"
                      class="text-[10px] px-1.5 py-0.5 rounded bg-blue-50 text-blue-700"
                    >
                      {{ ch.label }}:
                      <span v-if="ch.from" class="text-blue-400 line-through">{{ ch.from }}</span>
                      <span v-if="ch.from"> → </span>{{ ch.to }}
                    </span>
                  </div>
                </div>
                <p v-if="row.ads.length > 50" class="text-[11px] text-gray-400 px-1">
                  + {{ row.ads.length - 50 }} anúncio(s) — ver detalhes
                </p>
              </div>
            </div>

            <!-- Ver detalhes -->
            <button
              @click="openDetail(row.op.key)"
              class="inline-flex items-center gap-1.5 text-sm font-medium text-meli-blue hover:underline"
            >
              Ver detalhes <ArrowRight :size="15" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="bg-white rounded-2xl border shadow-sm p-8 text-center">
      <p class="text-gray-500">Nenhuma operação registrada.</p>
    </div>

    <!-- Paginação -->
    <div v-if="paging.total > 0" class="flex items-center justify-between mt-4 text-sm text-gray-500">
      <span>{{ paging.total }} operação(ões)</span>
      <div class="flex items-center gap-2">
        <button
          @click="prevPage"
          :disabled="filters.offset === 0"
          class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
        >
          <ChevronLeft :size="18" />
        </button>
        <span>
          {{ Math.floor(filters.offset! / filters.limit!) + 1 }} /
          {{ Math.ceil(paging.total / filters.limit!) || 1 }}
        </span>
        <button
          @click="nextPage"
          :disabled="filters.offset! + filters.limit! >= paging.total"
          class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
        >
          <ChevronRight :size="18" />
        </button>
      </div>
    </div>
  </div>
</template>

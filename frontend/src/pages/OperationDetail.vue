<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getOperationDetail, type OperationLog, type OperationGroup } from "@/api/logs";
import { useAuthStore } from "@/stores/auth";
import {
  operationLabel,
  affectedAds,
  operationChanges,
  listingTypeLabel,
  TYPE_LABELS,
  operationDisplayStats,
} from "@/lib/opHistory";
import {
  ArrowLeft,
  Loader2,
  CheckCircle,
  XCircle,
  AlertTriangle,
  ExternalLink,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const key = route.params.key as string;
const rows = ref<OperationLog[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    const resp = await getOperationDetail(key);
    rows.value = resp.rows;
  } finally {
    loading.value = false;
  }
}
onMounted(load);

// Monta um "grupo" sintético a partir das linhas pra reaproveitar os helpers.
const group = computed<OperationGroup>(() => {
  const r = rows.value;
  const success = r.filter((x) => x.status === "success").length;
  const error = r.filter((x) => x.status === "error").length;
  const partial = r.filter((x) => x.status === "partial").length;
  const status: OperationGroup["status"] =
    partial > 0 || (error > 0 && success > 0) ? "partial" : error > 0 ? "error" : "success";
  return {
    key,
    operation_type: r[0]?.operation_type || "",
    actor: r[0]?.actor ?? null,
    status,
    total: r.length,
    success,
    error,
    partial,
    created_at: r[0]?.created_at || null,
    children: r,
  };
});

const ads = computed(() => affectedAds(group.value));
const changes = computed(() => operationChanges(group.value));
const displayStats = computed(() => operationDisplayStats(group.value));
const rejectionGroups = computed(() => {
  const grouped = new Map<string, number>();
  for (const ad of ads.value) {
    if (ad.status !== "error" || !ad.errorMessage) continue;
    grouped.set(ad.errorMessage, (grouped.get(ad.errorMessage) || 0) + 1);
  }
  return [...grouped.entries()].map(([message, count]) => ({ message, count }));
});

// MLB -> permalink (para clones, vem na response)
const permalinks = computed(() => {
  const map: Record<string, string> = {};
  for (const r of rows.value) {
    const mlb = r.item_ids?.[0];
    const link = (r.response as any)?.permalink;
    if (mlb && link) map[mlb] = link;
  }
  return map;
});

function nickname(userId: number | null | undefined): string {
  if (!userId) return "";
  const acc = auth.accounts.find((a) => a.user_id === userId);
  return acc?.nickname || `Conta ${userId}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR", {
    day: "2-digit", month: "2-digit", year: "numeric",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
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
</script>

<template>
  <div>
    <button
      @click="router.back()"
      class="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-800 mb-4"
    >
      <ArrowLeft :size="16" /> Voltar ao histórico
    </button>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <template v-else-if="rows.length">
      <!-- Cabeçalho -->
      <div class="flex items-center gap-3 mb-6">
        <component :is="sIcon(group.status).icon" :size="26" :class="sIcon(group.status).color" />
        <div>
          <h2 class="text-2xl font-extrabold tracking-tight">{{ operationLabel(group) }}</h2>
          <p class="text-sm text-gray-500">
            {{ formatDate(group.created_at) }} · {{ displayStats.success }}/{{ displayStats.total }} com sucesso
            <span v-if="displayStats.error" class="text-red-500">
              · {{ displayStats.error }} {{ displayStats.isPromotion ? 'rejeitado(s) pelo ML' : 'erro(s)' }}
            </span>
            <span v-if="group.actor"> · por <span class="font-medium text-gray-700">{{ group.actor }}</span></span>
          </p>
        </div>
      </div>

      <div
        v-if="rejectionGroups.length"
        class="mb-5 rounded-xl border border-red-200 bg-red-50/70 px-4 py-3 text-sm text-red-600 dark:border-red-900/50 dark:bg-red-900/15 dark:text-red-300"
      >
        <p class="font-semibold mb-1">O Mercado Livre rejeitou parte dos anúncios</p>
        <p v-for="reason in rejectionGroups" :key="reason.message" class="mt-1">
          <strong>{{ reason.count }} anúncio(s):</strong> {{ reason.message }}
        </p>
      </div>

      <!-- O que mudou -->
      <div v-if="changes.length" class="mb-4">
        <div class="flex gap-1.5 flex-wrap">
          <span
            v-for="c in changes"
            :key="c"
            class="text-[11px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-700"
          >{{ c }}</span>
        </div>
      </div>

      <!-- Log amigável do resultado -->
      <div class="bg-white rounded-2xl border shadow-sm overflow-hidden mb-5">
        <div class="px-4 py-2.5 border-b bg-gray-50">
          <h3 class="text-xs font-bold uppercase tracking-wide text-gray-500">Resultado por anúncio</h3>
        </div>
        <div class="divide-y">
          <div
            v-for="(ad, i) in ads"
            :key="(ad.mlb || '') + i"
            class="px-4 py-2 text-sm"
          >
            <div class="flex items-center gap-2.5">
              <component :is="sIcon(ad.status).icon" :size="14" :class="sIcon(ad.status).color" class="flex-shrink-0" />
              <span v-if="ad.sku" class="font-mono font-medium text-gray-700 flex-shrink-0">{{ ad.sku }}</span>
              <span class="text-gray-700 truncate flex-1">{{ ad.title || ad.mlb || "—" }}</span>
              <span
                v-if="ad.actionLabel"
                class="text-[10px] px-1.5 py-0.5 rounded font-medium flex-shrink-0"
                :class="ad.status === 'error' ? 'bg-red-50 text-red-600' : 'bg-gray-800 text-white'"
              >{{ ad.actionLabel }}</span>
              <span
                v-if="ad.listingType"
                class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600 flex-shrink-0"
              >{{ listingTypeLabel(ad.listingType) }}</span>
              <span v-if="ad.userId" class="text-xs text-gray-400 flex-shrink-0">{{ nickname(ad.userId) }}</span>
              <a
                v-if="ad.mlb && permalinks[ad.mlb]"
                :href="permalinks[ad.mlb]"
                target="_blank"
                rel="noopener"
                class="text-meli-blue hover:text-meli-blue-dark flex-shrink-0"
                title="Abrir no Mercado Livre"
              >
                <ExternalLink :size="14" />
              </a>
            </div>
            <!-- Mudanças campo a campo: "Status: Pausado → Ativo" -->
            <div v-if="ad.changes && ad.changes.length" class="flex flex-wrap gap-1 mt-1 pl-[26px]">
              <span
                v-for="ch in ad.changes"
                :key="ch.field"
                class="text-[11px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-700"
              >
                {{ ch.label }}:
                <span v-if="ch.from" class="text-blue-400 line-through">{{ ch.from }}</span>
                <span v-if="ch.from"> → </span>{{ ch.to }}
              </span>
            </div>
            <p v-if="ad.errorMessage" class="mt-1 pl-[26px] text-xs text-red-600">
              {{ ad.errorMessage }}
              <span v-if="ad.errorCode" class="text-red-400">({{ ad.errorCode }})</span>
            </p>
          </div>
        </div>
      </div>

      <!-- Log técnico -->
      <div class="bg-white rounded-2xl border shadow-sm overflow-hidden">
        <div class="px-4 py-2.5 border-b bg-gray-50">
          <h3 class="text-xs font-bold uppercase tracking-wide text-gray-500">Log técnico</h3>
        </div>
        <div class="divide-y">
          <details v-for="r in rows" :key="r.id" class="px-4 py-3">
            <summary class="flex items-center gap-2.5 cursor-pointer text-sm">
              <component :is="sIcon(r.status).icon" :size="14" :class="sIcon(r.status).color" class="flex-shrink-0" />
              <span class="font-medium text-gray-700">{{ TYPE_LABELS[r.operation_type] || r.operation_type }}</span>
              <span class="text-gray-400 text-xs">{{ (r.item_ids || []).join(", ") || "—" }}</span>
            </summary>
            <div class="mt-2 space-y-2">
              <div v-if="r.error_message">
                <p class="text-[10px] text-red-500 uppercase mb-1">Erro</p>
                <p class="text-xs text-red-600 bg-red-50 p-2 rounded border border-red-200 break-all">{{ r.error_message }}</p>
              </div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-2">
                <div>
                  <p class="text-[10px] text-gray-400 mb-1 uppercase">Payload</p>
                  <pre class="text-[10px] bg-gray-50 p-2 rounded border overflow-auto max-h-72">{{ JSON.stringify(r.payload, null, 2) }}</pre>
                </div>
                <div>
                  <p class="text-[10px] text-gray-400 mb-1 uppercase">Resposta</p>
                  <pre class="text-[10px] bg-gray-50 p-2 rounded border overflow-auto max-h-72">{{ JSON.stringify(r.response, null, 2) }}</pre>
                </div>
              </div>
            </div>
          </details>
        </div>
      </div>
    </template>

    <div v-else class="bg-white rounded-2xl border shadow-sm p-8 text-center">
      <p class="text-gray-500">Operação não encontrada.</p>
    </div>
  </div>
</template>

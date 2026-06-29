<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { getAtendimentoLogs, type OperationLog } from "@/api/logs";

// `actor` vem da página-mãe (Histórico) que tem o filtro por usuário.
const props = defineProps<{ actor?: string }>();
import { TYPE_LABELS } from "@/lib/opHistory";
import SelectMenu from "@/components/SelectMenu.vue";
import {
  Loader2,
  CheckCircle,
  XCircle,
  ChevronLeft,
  ChevronRight,
  MessageCircle,
  MessageSquare,
  User,
} from "lucide-vue-next";

const logs = ref<OperationLog[]>([]);
const paging = ref({ total: 0, offset: 0, limit: 50 });
const loading = ref(false);
const typeFilter = ref("");
const offset = ref(0);
const limit = 50;
const expandedId = ref<number | null>(null);

async function load() {
  loading.value = true;
  try {
    const resp = await getAtendimentoLogs({
      operation_type: typeFilter.value || undefined,
      actor: props.actor || undefined,
      offset: offset.value,
      limit,
    });
    logs.value = resp.logs;
    paging.value = resp.paging;
  } finally {
    loading.value = false;
  }
}

function applyFilter() {
  offset.value = 0;
  load();
}
function prevPage() {
  if (offset.value > 0) {
    offset.value = Math.max(0, offset.value - limit);
    load();
  }
}
function nextPage() {
  if (offset.value + limit < paging.value.total) {
    offset.value += limit;
    load();
  }
}
onMounted(load);

// Recarrega quando o filtro de usuário (vindo da página-mãe) muda.
watch(() => props.actor, () => { offset.value = 0; load(); });

function p(log: OperationLog): Record<string, any> {
  return (log.payload || {}) as Record<string, any>;
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("pt-BR", {
    day: "2-digit", month: "2-digit", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}
</script>

<template>
  <div>
    <!-- Filtro -->
    <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4">
      <div class="flex flex-wrap gap-3 items-end">
        <div class="min-w-[200px]">
          <label class="block text-xs text-gray-500 mb-1">Tipo</label>
          <SelectMenu
            v-model="typeFilter"
            :options="[
              { value: 'answer_question', label: 'Respostas de perguntas' },
              { value: 'send_message', label: 'Mensagens enviadas' },
            ]"
            @update:model-value="applyFilter"
          />
        </div>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <div v-else-if="logs.length" class="bg-white rounded-2xl border shadow-sm overflow-hidden">
      <div class="divide-y">
        <div v-for="log in logs" :key="log.id">
          <div
            class="flex items-start gap-3 px-4 py-3 cursor-pointer hover:bg-gray-50 transition-colors"
            @click="expandedId = expandedId === log.id ? null : log.id"
          >
            <component
              :is="log.operation_type === 'answer_question' ? MessageCircle : MessageSquare"
              :size="18"
              class="text-meli-blue flex-shrink-0 mt-0.5"
            />
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-semibold text-gray-900 text-sm">
                  {{ TYPE_LABELS[log.operation_type] || log.operation_type }}
                </span>
                <span v-if="p(log).nickname" class="text-xs text-gray-500">· {{ p(log).nickname }}</span>
                <span class="text-xs text-gray-400">· {{ formatDate(log.created_at) }}</span>
                <span
                  v-if="log.actor"
                  class="text-[11px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-600"
                  title="Quem respondeu"
                >
                  <User :size="11" /> {{ log.actor }}
                </span>
              </div>
              <!-- Pergunta respondida -->
              <p v-if="log.operation_type === 'answer_question' && p(log).question_text"
                 class="text-xs text-gray-400 truncate mt-0.5">
                Pergunta: {{ p(log).question_text }}
              </p>
              <p class="text-sm text-gray-700 truncate">
                {{ p(log).answer_text || p(log).text || "—" }}
              </p>
            </div>
            <component
              :is="log.status === 'success' ? CheckCircle : XCircle"
              :size="16"
              :class="log.status === 'success' ? 'text-green-500' : 'text-red-500'"
              class="flex-shrink-0 mt-0.5"
            />
          </div>

          <!-- Detalhe técnico -->
          <div v-if="expandedId === log.id" class="bg-gray-50 border-t px-4 py-3 space-y-2">
            <div v-if="p(log).question_text">
              <p class="text-[10px] text-gray-400 uppercase mb-1">Pergunta</p>
              <p class="text-sm bg-white p-2 rounded border">{{ p(log).question_text }}</p>
            </div>
            <div>
              <p class="text-[10px] text-gray-400 uppercase mb-1">
                {{ log.operation_type === 'answer_question' ? 'Resposta' : 'Mensagem' }}
              </p>
              <p class="text-sm bg-white p-2 rounded border whitespace-pre-wrap">
                {{ p(log).answer_text || p(log).text || "—" }}
              </p>
            </div>
            <div v-if="log.error_message">
              <p class="text-[10px] text-red-500 uppercase mb-1">Erro</p>
              <p class="text-xs text-red-600 bg-red-50 p-2 rounded border border-red-200 break-all">
                {{ log.error_message }}
              </p>
            </div>
            <details>
              <summary class="text-xs text-gray-400 cursor-pointer hover:text-gray-600">Ver dados técnicos</summary>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-2 mt-2">
                <div>
                  <p class="text-[10px] text-gray-400 mb-1 uppercase">Payload</p>
                  <pre class="text-[10px] bg-white p-2 rounded border overflow-auto max-h-60">{{ JSON.stringify(log.payload, null, 2) }}</pre>
                </div>
                <div>
                  <p class="text-[10px] text-gray-400 mb-1 uppercase">Resposta</p>
                  <pre class="text-[10px] bg-white p-2 rounded border overflow-auto max-h-60">{{ JSON.stringify(log.response, null, 2) }}</pre>
                </div>
              </div>
            </details>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="bg-white rounded-2xl border shadow-sm p-8 text-center">
      <p class="text-gray-500">Nenhum atendimento registrado ainda.</p>
    </div>

    <!-- Paginação -->
    <div v-if="paging.total > 0" class="flex items-center justify-between mt-4 text-sm text-gray-500">
      <span>{{ paging.total }} registro(s)</span>
      <div class="flex items-center gap-2">
        <button
          @click="prevPage"
          :disabled="offset === 0"
          class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
        >
          <ChevronLeft :size="18" />
        </button>
        <span>{{ Math.floor(offset / limit) + 1 }} / {{ Math.ceil(paging.total / limit) || 1 }}</span>
        <button
          @click="nextPage"
          :disabled="offset + limit >= paging.total"
          class="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-30 disabled:cursor-not-allowed"
        >
          <ChevronRight :size="18" />
        </button>
      </div>
    </div>
  </div>
</template>

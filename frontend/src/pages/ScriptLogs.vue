<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import {
  listScriptLogs,
  deleteScriptLog,
  downloadScriptLogUrl,
  type ScriptLogEntry,
} from "@/api/scriptLogs";
import { Download, Trash2, Loader2, RefreshCw, FileSpreadsheet } from "lucide-vue-next";

const logs = ref<ScriptLogEntry[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const deleting = ref<string | null>(null);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    logs.value = await listScriptLogs();
  } catch (e) {
    error.value = "Falha ao carregar lista de planilhas.";
    console.error(e);
  } finally {
    loading.value = false;
  }
}

async function onDelete(entry: ScriptLogEntry) {
  if (!confirm(`Apagar a planilha "${entry.filename}"?\nEssa ação não pode ser desfeita.`)) {
    return;
  }
  deleting.value = entry.filename;
  try {
    await deleteScriptLog(entry.filename);
    logs.value = logs.value.filter((l) => l.filename !== entry.filename);
  } catch (e) {
    alert("Erro ao apagar.");
    console.error(e);
  } finally {
    deleting.value = null;
  }
}

function formatDateTime(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return iso;
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

// Agrupa por script_key pra exibir em seções
const grouped = computed(() => {
  const map: Record<string, ScriptLogEntry[]> = {};
  for (const log of logs.value) {
    if (!map[log.script_key]) map[log.script_key] = [];
    map[log.script_key].push(log);
  }
  return Object.entries(map).map(([key, items]) => ({
    key,
    label: items[0].label,
    items,
  }));
});

onMounted(load);
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 dark:text-gray-400 mb-0.5">
          Exportações
        </p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight dark:text-gray-100">Planilhas de Scripts</h2>
        <p class="text-sm text-gray-500 dark:text-gray-400 mt-0.5">
          Resultados gerados pelos scripts de execução em lote
        </p>
      </div>
      <button
        @click="load"
        class="flex items-center gap-2 px-3 py-2 text-sm font-medium rounded-lg
               bg-white hover:bg-gray-50 border border-gray-300
               dark:bg-zinc-800 dark:hover:bg-zinc-700 dark:border-zinc-700 dark:text-gray-200"
        :disabled="loading"
      >
        <RefreshCw :size="16" :class="loading ? 'animate-spin' : ''" />
        Atualizar
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading && logs.length === 0" class="flex items-center justify-center py-16 text-gray-400">
      <Loader2 :size="28" class="animate-spin" />
    </div>

    <!-- Error -->
    <div
      v-else-if="error"
      class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800
             text-red-700 dark:text-red-400 rounded-lg p-4 text-sm"
    >
      {{ error }}
    </div>

    <!-- Empty -->
    <div
      v-else-if="logs.length === 0"
      class="bg-white dark:bg-brand-black-soft border dark:border-zinc-800 rounded-xl p-10 text-center"
    >
      <FileSpreadsheet :size="40" class="mx-auto text-gray-300 dark:text-zinc-600 mb-3" />
      <p class="text-gray-500 dark:text-gray-400">
        Nenhuma planilha gerada ainda. Rode algum script (ex.:
        <code class="text-xs bg-gray-100 dark:bg-zinc-800 px-1.5 py-0.5 rounded">
          sync_clones_between_accounts
        </code>) e os resultados aparecem aqui.
      </p>
    </div>

    <!-- Lista agrupada -->
    <div v-else class="space-y-6">
      <section
        v-for="group in grouped"
        :key="group.key"
        class="bg-white dark:bg-brand-black-soft border dark:border-zinc-800 rounded-xl overflow-hidden"
      >
        <header class="px-5 py-3 border-b dark:border-zinc-800 bg-gray-50 dark:bg-zinc-900/40">
          <h3 class="font-semibold text-sm text-gray-800 dark:text-gray-200">
            {{ group.label }}
            <span class="ml-2 text-xs font-normal text-gray-500 dark:text-gray-400">
              {{ group.items.length }} {{ group.items.length === 1 ? "planilha" : "planilhas" }}
            </span>
          </h3>
        </header>

        <ul class="divide-y dark:divide-zinc-800">
          <li
            v-for="entry in group.items"
            :key="entry.filename"
            class="flex items-center gap-3 px-5 py-3 hover:bg-gray-50 dark:hover:bg-zinc-900/40 transition-colors"
          >
            <FileSpreadsheet :size="20" class="text-green-600 dark:text-green-400 flex-shrink-0" />

            <div class="min-w-0 flex-1">
              <div class="font-medium text-sm text-gray-900 dark:text-gray-100 truncate">
                Script de {{ formatDateTime(entry.datetime) }}
                <span class="ml-2 text-xs font-normal text-gray-500 dark:text-gray-400">
                  · {{ entry.row_count }} {{ entry.row_count === 1 ? "anúncio" : "anúncios" }}
                </span>
              </div>
              <div class="text-[11px] text-gray-400 dark:text-gray-500 truncate">
                {{ entry.filename }} · {{ formatSize(entry.size_bytes) }}
              </div>
            </div>

            <a
              :href="downloadScriptLogUrl(entry.filename)"
              :download="entry.filename"
              class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg
                     bg-meli-blue text-brand-yellow hover:bg-meli-blue-dark
                     dark:bg-brand-yellow dark:text-brand-black dark:hover:bg-brand-yellow-dark"
              title="Baixar"
            >
              <Download :size="14" />
              Baixar
            </a>

            <button
              type="button"
              @click="onDelete(entry)"
              :disabled="deleting === entry.filename"
              class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg
                     text-red-600 hover:bg-red-50 border border-red-200
                     dark:text-red-400 dark:hover:bg-red-900/30 dark:border-red-900/40
                     disabled:opacity-50"
              title="Apagar"
            >
              <Loader2 v-if="deleting === entry.filename" :size="14" class="animate-spin" />
              <Trash2 v-else :size="14" />
              Apagar
            </button>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

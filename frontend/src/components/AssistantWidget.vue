<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { Bot, X, SendHorizonal, Loader2, Trash2, Search, Check } from "lucide-vue-next";
import { marked } from "marked";
import { useAssistantStore } from "@/stores/assistant";
import { useAuthStore } from "@/stores/auth";

// Links do modelo: só http(s), mailto, âncora ou caminho — derruba javascript:/data: etc.
const SAFE_HREF = /^(https?:|mailto:|#|\/)/i;
marked.use({
  walkTokens(token) {
    if (
      (token.type === "link" || token.type === "image") &&
      !SAFE_HREF.test(token.href.replace(/[\u0000-\u0020]/g, ""))
    ) {
      token.href = "#";
    }
  },
});

const store = useAssistantStore();
const auth = useAuthStore();
const draft = ref("");
const scrollArea = ref<HTMLElement | null>(null);

// Markdown seguro: escapa HTML do modelo ANTES do marked → nada de tag injetada.
function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function md(s: string): string {
  return marked.parse(escapeHtml(s), { async: false, breaks: true }) as string;
}

async function open() {
  store.isOpen = true;
  if (auth.isAdmin) await store.ensureModels();
  scrollToEnd();
}

async function onModelChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const selected = input.value;
  try {
    await store.selectModel(selected);
  } catch (e) {
    input.value = store.model || "";
    console.error("Falha ao alterar modelo do OpenRouter:", e);
  }
}

function scrollToEnd() {
  nextTick(() => {
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight;
  });
}

watch(() => [store.entries.length, store.loading, store.pendingAction], scrollToEnd);

async function submit() {
  const text = draft.value;
  draft.value = "";
  await store.send(text);
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    submit();
  }
}
</script>

<template>
  <!-- Botão flutuante (acima do toggle de tema, que usa bottom-4) -->
  <button
    v-if="!store.isOpen"
    @click="open"
    class="fixed bottom-20 right-4 z-50 w-11 h-11 rounded-full flex items-center justify-center shadow-lg ring-1 ring-black/10 bg-indigo-600 text-white hover:bg-indigo-500 transition-colors"
    title="Assistente de IA"
    aria-label="Abrir assistente de IA"
  >
    <Bot :size="22" />
  </button>

  <!-- Painel -->
  <div
    v-if="store.isOpen"
    class="fixed bottom-4 right-4 z-50 flex flex-col w-[min(420px,calc(100vw-2rem))] h-[min(640px,calc(100vh-2rem))] rounded-xl shadow-2xl ring-1 ring-black/10 dark:ring-white/10 bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100"
  >
    <!-- Header -->
    <div class="flex items-center gap-2 px-3 py-2 border-b border-zinc-200 dark:border-zinc-700">
      <Bot :size="18" class="text-indigo-500 shrink-0" />
      <span class="font-semibold text-sm">Assistente</span>
      <input
        v-if="auth.isAdmin"
        :value="store.model || ''"
        @change="onModelChange"
        :disabled="store.modelSaving"
        list="openrouter-model-catalog"
        placeholder="Buscar modelo..."
        class="ml-auto w-[180px] text-xs rounded border border-zinc-300 dark:border-zinc-600 bg-transparent px-1.5 py-1"
        title="Modelo global de IA (somente administradores)"
      />
      <datalist v-if="auth.isAdmin" id="openrouter-model-catalog">
        <option v-for="m in store.models" :key="m.id" :value="m.id">{{ m.name }}</option>
      </datalist>
      <button @click="store.clear()" class="p-1 rounded hover:bg-zinc-100 dark:hover:bg-zinc-800" title="Limpar conversa">
        <Trash2 :size="16" />
      </button>
      <button @click="store.isOpen = false" class="p-1 rounded hover:bg-zinc-100 dark:hover:bg-zinc-800" title="Fechar">
        <X :size="16" />
      </button>
    </div>

    <!-- Mensagens -->
    <div ref="scrollArea" class="flex-1 overflow-y-auto px-3 py-2 space-y-2">
      <p v-if="store.entries.length === 0" class="text-sm text-zinc-500 dark:text-zinc-400 mt-4 text-center">
        Pergunte sobre suas contas: performance, anúncios, perguntas, promoções…<br />
        Eu também posso alterar anúncios — sempre com a sua confirmação.
      </p>

      <template v-for="(entry, i) in store.entries" :key="i">
        <!-- Evento de tool -->
        <div v-if="entry.role === 'event'" class="flex items-center gap-1.5 text-[11px] text-zinc-400 dark:text-zinc-500 pl-1">
          <Search :size="11" class="shrink-0" />
          <span>{{ entry.content }}</span>
        </div>
        <!-- Usuário -->
        <div v-else-if="entry.role === 'user'" class="flex justify-end">
          <div class="max-w-[85%] rounded-lg px-3 py-1.5 text-sm bg-indigo-600 text-white whitespace-pre-wrap">{{ entry.content }}</div>
        </div>
        <!-- Assistente (markdown) -->
        <div v-else class="flex">
          <div
            class="max-w-[85%] rounded-lg px-3 py-1.5 text-sm bg-zinc-100 dark:bg-zinc-800 prose prose-sm dark:prose-invert prose-p:my-1 prose-pre:my-1 prose-pre:text-xs prose-table:text-xs max-w-none overflow-x-auto"
            v-html="md(entry.content)"
          />
        </div>
      </template>

      <!-- Card de confirmação -->
      <div
        v-if="store.pendingAction"
        class="rounded-lg border-2 border-amber-400 dark:border-amber-500 bg-amber-50 dark:bg-amber-950/40 p-3 text-sm"
      >
        <p class="font-semibold mb-1">Confirmar ação?</p>
        <p class="mb-2">{{ store.pendingAction.summary }}</p>
        <div class="flex gap-2">
          <button
            @click="store.confirm()"
            :disabled="store.loading"
            class="flex items-center gap-1 rounded bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold px-3 py-1.5 disabled:opacity-50"
          >
            <Check :size="14" /> Confirmar
          </button>
          <button
            @click="store.reject()"
            :disabled="store.loading"
            class="rounded bg-zinc-200 dark:bg-zinc-700 hover:bg-zinc-300 dark:hover:bg-zinc-600 text-xs font-semibold px-3 py-1.5 disabled:opacity-50"
          >
            Cancelar
          </button>
        </div>
      </div>

      <!-- Pensando... -->
      <div v-if="store.loading" class="flex items-center gap-2 text-xs text-zinc-400">
        <Loader2 :size="14" class="animate-spin" /> pensando…
      </div>
    </div>

    <!-- Input -->
    <div class="flex items-end gap-2 p-2 border-t border-zinc-200 dark:border-zinc-700">
      <textarea
        v-model="draft"
        @keydown="onKeydown"
        rows="2"
        placeholder="Pergunte algo… (Enter envia)"
        class="flex-1 resize-none rounded-lg border border-zinc-300 dark:border-zinc-600 bg-transparent px-2 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
      />
      <button
        @click="submit"
        :disabled="store.loading || !draft.trim()"
        class="p-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-500 disabled:opacity-40"
        title="Enviar"
      >
        <SendHorizonal :size="16" />
      </button>
    </div>
  </div>
</template>

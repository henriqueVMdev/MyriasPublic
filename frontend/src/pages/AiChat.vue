<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from "vue";
import {
  Bot,
  Check,
  Search,
  SendHorizonal,
  Sparkles,
  Trash2,
} from "lucide-vue-next";
import { useAssistantStore } from "@/stores/assistant";
import { useAuthStore } from "@/stores/auth";
import { renderMarkdown } from "@/lib/markdown";
import AiLoader from "@/components/ui/AiLoader.vue";

// Mesma store do widget flutuante: a conversa é uma só, abrir a página
// não recomeça o histórico nem duplica a chamada ao backend.
const store = useAssistantStore();
const auth = useAuthStore();

const draft = ref("");
const scrollArea = ref<HTMLElement | null>(null);
const composer = ref<HTMLTextAreaElement | null>(null);

const SUGGESTIONS = [
  "Como está a performance dos meus anúncios esta semana?",
  "Quais perguntas ainda não foram respondidas?",
  "Tem algum anúncio repetido no catálogo?",
  "Resuma as promoções ativas agora",
];

onMounted(async () => {
  if (auth.isAdmin) await store.ensureModels();
  scrollToEnd();
  composer.value?.focus();
});

function scrollToEnd() {
  nextTick(() => {
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight;
  });
}

watch(() => [store.entries.length, store.loading, store.pendingAction], scrollToEnd);

async function submit() {
  const text = draft.value;
  if (!text.trim() || store.loading) return;
  draft.value = "";
  await store.send(text);
  composer.value?.focus();
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    submit();
  }
}

function askSuggestion(text: string) {
  draft.value = text;
  submit();
}

async function onModelChange(event: Event) {
  const input = event.target as HTMLInputElement;
  try {
    await store.selectModel(input.value);
  } catch (e) {
    input.value = store.model || "";
    console.error("Falha ao alterar modelo do OpenRouter:", e);
  }
}
</script>

<template>
  <!-- `relative` ancora o loader de fundo; `h-full` vem do wrapper do Layout -->
  <div class="relative flex h-full flex-col">
    <!--
      Loader de fundo: some sozinho quando a resposta da IA chega, porque
      está preso ao `store.loading`. Fica atrás de tudo (-z-0 + pointer-events)
      e escala com o breakpoint — no celular o orbe de 12em estouraria a tela.
    -->
    <transition name="fade-in">
      <div
        v-if="store.loading"
        class="pointer-events-none absolute inset-0 flex items-center justify-center"
        aria-hidden="true"
      >
        <AiLoader
          text="Gerando"
          class="text-[0.5rem] opacity-60 sm:text-[0.7rem] lg:text-[0.9rem] dark:opacity-70"
        />
      </div>
    </transition>

    <!-- Cabeçalho -->
    <header
      class="relative z-10 flex items-center gap-2 border-b border-gray-200 pb-3 dark:border-zinc-800"
    >
      <Bot :size="20" class="shrink-0 text-brand-yellow-deep dark:text-brand-yellow" />
      <div class="min-w-0">
        <h1 class="truncate text-lg font-bold text-gray-900 dark:text-gray-100">
          Assistente
        </h1>
        <p class="hidden text-xs text-gray-500 sm:block">
          Pergunte sobre suas contas — posso consultar e, com sua confirmação, alterar anúncios.
        </p>
      </div>

      <input
        v-if="auth.isAdmin"
        :value="store.model || ''"
        @change="onModelChange"
        :disabled="store.modelSaving"
        list="ai-chat-model-catalog"
        placeholder="Buscar modelo…"
        class="ml-auto w-[150px] rounded-lg border border-gray-300 bg-transparent px-2 py-1 text-xs sm:w-[220px] dark:border-zinc-700"
        title="Modelo global de IA (somente administradores)"
      />
      <datalist v-if="auth.isAdmin" id="ai-chat-model-catalog">
        <option v-for="m in store.models" :key="m.id" :value="m.id">{{ m.name }}</option>
      </datalist>

      <button
        @click="store.clear()"
        :disabled="store.entries.length === 0"
        class="rounded-lg p-2 text-gray-600 transition-colors hover:bg-brand-yellow-soft disabled:opacity-40 dark:text-gray-300 dark:hover:bg-zinc-800"
        :class="auth.isAdmin ? '' : 'ml-auto'"
        title="Limpar conversa"
      >
        <Trash2 :size="16" />
      </button>
    </header>

    <!-- Mensagens -->
    <div ref="scrollArea" class="relative z-10 flex-1 space-y-3 overflow-y-auto py-4">
      <!-- Estado vazio: sugestões pra primeira pergunta -->
      <div
        v-if="store.entries.length === 0 && !store.loading"
        class="mx-auto flex max-w-2xl flex-col items-center gap-4 pt-10 text-center"
      >
        <Sparkles :size="28" class="text-brand-yellow-deep dark:text-brand-yellow" />
        <p class="text-sm text-gray-500">Por onde começamos?</p>
        <div class="grid w-full gap-2 sm:grid-cols-2">
          <button
            v-for="s in SUGGESTIONS"
            :key="s"
            @click="askSuggestion(s)"
            class="rounded-xl border border-gray-200 bg-white px-3 py-2.5 text-left text-sm text-gray-700 transition-colors hover:border-brand-yellow hover:bg-brand-yellow-soft dark:border-zinc-800 dark:bg-brand-black-soft dark:text-gray-300 dark:hover:border-brand-yellow dark:hover:bg-zinc-800"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <template v-for="(entry, i) in store.entries" :key="i">
        <!-- Evento de tool -->
        <div
          v-if="entry.role === 'event'"
          class="mx-auto flex max-w-3xl items-center gap-1.5 pl-1 text-[11px] text-gray-400"
        >
          <Search :size="11" class="shrink-0" />
          <span>{{ entry.content }}</span>
        </div>

        <!-- Usuário -->
        <div v-else-if="entry.role === 'user'" class="mx-auto flex max-w-3xl justify-end">
          <div
            class="max-w-[85%] whitespace-pre-wrap rounded-2xl bg-brand-black px-4 py-2 text-sm text-white dark:bg-brand-yellow dark:text-brand-black"
          >
            {{ entry.content }}
          </div>
        </div>

        <!-- Assistente (markdown já escapado) -->
        <div v-else class="mx-auto flex max-w-3xl">
          <div
            class="md-body max-w-[90%] rounded-2xl border border-gray-200 bg-white px-4 py-2.5 text-sm text-gray-800 shadow-sm dark:border-zinc-800 dark:bg-brand-black-soft dark:text-gray-200"
            v-html="renderMarkdown(entry.content)"
          />
        </div>
      </template>

      <!-- Card de confirmação de ação -->
      <div
        v-if="store.pendingAction"
        class="mx-auto max-w-3xl rounded-2xl border-2 border-amber-400 bg-amber-50 p-4 text-sm dark:border-amber-500 dark:bg-amber-950/40"
      >
        <p class="mb-1 font-bold">Confirmar ação?</p>
        <p class="mb-3">{{ store.pendingAction.summary }}</p>
        <div class="flex gap-2">
          <button
            @click="store.confirm()"
            :disabled="store.loading"
            class="flex items-center gap-1 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-bold text-white hover:bg-emerald-500 disabled:opacity-50"
          >
            <Check :size="14" /> Confirmar
          </button>
          <button
            @click="store.reject()"
            :disabled="store.loading"
            class="rounded-lg bg-gray-200 px-3 py-1.5 text-xs font-bold hover:bg-gray-300 disabled:opacity-50 dark:bg-zinc-700 dark:hover:bg-zinc-600"
          >
            Cancelar
          </button>
        </div>
      </div>
    </div>

    <!-- Composer -->
    <div
      class="relative z-10 flex items-end gap-2 rounded-2xl border border-gray-200 bg-white p-2 shadow-sm dark:border-zinc-800 dark:bg-brand-black-soft"
    >
      <textarea
        ref="composer"
        v-model="draft"
        @keydown="onKeydown"
        rows="1"
        :disabled="store.loading"
        placeholder="Pergunte algo… (Enter envia, Shift+Enter quebra linha)"
        class="max-h-40 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm focus:outline-none disabled:opacity-60"
      />
      <button
        @click="submit"
        :disabled="store.loading || !draft.trim()"
        class="rounded-xl bg-brand-black p-2.5 text-brand-yellow transition-colors hover:bg-brand-black-soft disabled:opacity-40 dark:bg-brand-yellow dark:text-brand-black dark:hover:bg-brand-yellow-dark"
        title="Enviar"
      >
        <SendHorizonal :size="16" />
      </button>
    </div>
  </div>
</template>

<style scoped>
/* O projeto não usa @tailwindcss/typography — as classes `prose` seriam no-op,
   então a tipografia do markdown do modelo vem daqui. */
.md-body :deep(p) {
  margin: 0.35rem 0;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.35rem 0;
  padding-left: 1.15rem;
}
.md-body :deep(ul) {
  list-style: disc;
}
.md-body :deep(ol) {
  list-style: decimal;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  margin: 0.6rem 0 0.3rem;
  font-weight: 700;
  font-size: 0.95rem;
}
.md-body :deep(a) {
  text-decoration: underline;
  text-underline-offset: 2px;
}
.md-body :deep(code) {
  border-radius: 0.25rem;
  background: rgba(26, 25, 21, 0.06);
  padding: 0.1rem 0.3rem;
  font-family: "JetBrains Mono", ui-monospace, monospace;
  font-size: 0.8em;
}
.dark .md-body :deep(code) {
  background: rgba(255, 255, 255, 0.1);
}
.md-body :deep(pre) {
  overflow-x: auto;
  border-radius: 0.5rem;
  background: rgba(26, 25, 21, 0.06);
  padding: 0.6rem 0.75rem;
  margin: 0.45rem 0;
}
.dark .md-body :deep(pre) {
  background: rgba(255, 255, 255, 0.07);
}
.md-body :deep(pre code) {
  background: none;
  padding: 0;
}
.md-body :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
  font-size: 0.8rem;
  margin: 0.45rem 0;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid rgba(26, 25, 21, 0.15);
  padding: 0.25rem 0.5rem;
  text-align: left;
}
.dark .md-body :deep(th),
.dark .md-body :deep(td) {
  border-color: rgba(255, 255, 255, 0.15);
}

/* Entrada suave do orbe de fundo */
.fade-in-enter-active,
.fade-in-leave-active {
  transition: opacity 0.25s ease;
}
.fade-in-enter-from,
.fade-in-leave-to {
  opacity: 0;
}
</style>

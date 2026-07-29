<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Bot, X, Send, Loader2, Trash2, Search, Check, Info, Paperclip, ImagePlus } from "lucide-vue-next";
import { marked } from "marked";
import { useAssistantStore } from "@/stores/assistant";
import { useAuthStore } from "@/stores/auth";
import { uploadPicture } from "@/api/items";

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

const MAX_CHARS = 2000;
const charCount = computed(() => draft.value.length);

// Imagens anexadas: sobem pro Mercado Livre na hora e viram URL + id de foto,
// que vão junto na mensagem — o agente usa em bulk_update_items (pictures).
interface Attachment { id: string; url: string; name: string }
const attachments = ref<Attachment[]>([]);
const uploadingImages = ref(0);
const uploadError = ref("");
const dragging = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

async function addFiles(files: Iterable<File>) {
  uploadError.value = "";
  for (const file of files) {
    if (!file.type.startsWith("image/")) continue;
    uploadingImages.value++;
    try {
      const resp = await uploadPicture(file);
      const url = resp.data?.variations?.[0]?.secure_url;
      if (!resp.data?.id || !url) throw new Error("resposta sem id/url");
      attachments.value.push({ id: resp.data.id, url, name: file.name });
    } catch (e: any) {
      uploadError.value =
        "Falha ao enviar " + file.name + ": " +
        (e?.response?.data?.detail || e?.message || "erro desconhecido");
    } finally {
      uploadingImages.value--;
    }
  }
}

function onDrop(e: DragEvent) {
  dragging.value = false;
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files);
}

function onPaste(e: ClipboardEvent) {
  const files = Array.from(e.clipboardData?.items || [])
    .filter((i) => i.kind === "file")
    .map((i) => i.getAsFile())
    .filter((f): f is File => !!f);
  if (files.length) {
    e.preventDefault();
    addFiles(files);
  }
}

function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement;
  if (input.files?.length) addFiles(input.files);
  input.value = "";
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1);
}

// Markdown seguro: escapa HTML do modelo ANTES do marked → nada de tag injetada.
function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function md(s: string): string {
  return marked.parse(escapeHtml(s), { async: false, breaks: true }) as string;
}

async function toggle() {
  if (store.isOpen) {
    store.isOpen = false;
    return;
  }
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
  let text = draft.value.trim();
  if ((!text && attachments.value.length === 0) || uploadingImages.value > 0) return;
  if (attachments.value.length > 0) {
    const refs = attachments.value
      .map((a, i) => `- imagem ${i + 1} (${a.name}): ${a.url} (picture_id: ${a.id})`)
      .join("\n");
    text = (text || "Enviei imagem(ns) para usar nas fotos de anúncios.")
      + "\n\n[Imagens já hospedadas no Mercado Livre:\n" + refs + "]";
  }
  draft.value = "";
  attachments.value = [];
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
  <!-- Acima do toggle de tema, que usa bottom-4 -->
  <div class="fixed bottom-20 right-4 z-50">
    <!-- Botão flutuante com glow -->
    <button
      @click="toggle"
      class="ai-fab relative w-14 h-14 rounded-full flex items-center justify-center transition-all duration-500 transform"
      :class="store.isOpen ? 'rotate-90' : 'rotate-0'"
      :title="store.isOpen ? 'Fechar assistente' : 'Assistente de IA'"
      aria-label="Abrir ou fechar assistente de IA"
    >
      <!-- Efeito 3D + brilho interno -->
      <span class="absolute inset-0 rounded-full bg-gradient-to-b from-white/30 to-transparent opacity-40"></span>
      <span class="absolute inset-0 rounded-full border-2 border-white/10"></span>
      <span class="relative z-10 text-brand-black">
        <X v-if="store.isOpen" :size="26" />
        <Bot v-else :size="28" />
      </span>
    </button>

    <!-- Painel -->
    <div
      v-if="store.isOpen"
      class="ai-panel absolute bottom-[4.5rem] right-0 w-[min(460px,calc(100vw-2rem))] origin-bottom-right"
    >
      <div
        class="relative flex flex-col h-[min(780px,calc(100vh-8rem))] rounded-3xl overflow-hidden
               bg-gradient-to-br from-zinc-800/90 to-zinc-900/95 border border-brand-yellow/40
               shadow-2xl backdrop-blur-3xl text-zinc-100"
        @dragover.prevent="dragging = true"
        @dragleave.self="dragging = false"
        @drop.prevent="onDrop"
      >
        <!-- Zona de drop -->
        <div
          v-if="dragging"
          class="absolute inset-0 z-20 flex flex-col items-center justify-center gap-2 rounded-3xl
                 bg-brand-yellow/15 border-2 border-dashed border-brand-yellow pointer-events-none"
        >
          <ImagePlus :size="36" class="text-brand-yellow" />
          <p class="text-sm font-semibold text-brand-yellow">Solte as imagens aqui</p>
        </div>
        <!-- Header -->
        <div class="flex items-center justify-between px-5 pt-4 pb-2">
          <div class="flex items-center gap-1.5">
            <span class="w-2 h-2 rounded-full bg-green-500"></span>
            <span class="text-xs font-semibold text-brand-yellow">Assistente IA</span>
          </div>
          <div class="flex items-center gap-2 min-w-0">
            <input
              v-if="auth.isAdmin"
              :value="store.model || ''"
              @change="onModelChange"
              :disabled="store.modelSaving"
              list="openrouter-model-catalog"
              placeholder="Buscar modelo…"
              class="w-[150px] text-xs rounded-2xl border border-zinc-700/60 bg-zinc-800/60 text-zinc-300 px-2.5 py-1 focus:outline-none focus:border-brand-yellow/60"
              title="Modelo global de IA (somente administradores)"
            />
            <span
              v-else-if="store.model"
              class="px-2 py-1 text-xs font-medium bg-zinc-800/60 text-zinc-300 rounded-2xl truncate max-w-[150px]"
            >
              {{ store.model }}
            </span>
            <datalist v-if="auth.isAdmin" id="openrouter-model-catalog">
              <option v-for="m in store.models" :key="m.id" :value="m.id">{{ m.name }}</option>
            </datalist>
            <button
              @click="store.clear()"
              class="p-1.5 rounded-full hover:bg-zinc-700/50 transition-colors"
              title="Limpar conversa"
            >
              <Trash2 :size="15" class="text-zinc-400" />
            </button>
            <button
              @click="store.isOpen = false"
              class="p-1.5 rounded-full hover:bg-zinc-700/50 transition-colors"
              title="Fechar"
            >
              <X :size="16" class="text-zinc-400" />
            </button>
          </div>
        </div>

        <!-- Mensagens -->
        <div ref="scrollArea" class="flex-1 overflow-y-auto px-4 py-2 space-y-2">
          <p v-if="store.entries.length === 0" class="text-sm text-zinc-500 mt-6 text-center px-4">
            Pergunte sobre suas contas: performance, anúncios, perguntas, promoções…<br />
            Eu também posso alterar anúncios — sempre com a sua confirmação.
          </p>

          <template v-for="(entry, i) in store.entries" :key="i">
            <!-- Evento de tool -->
            <div v-if="entry.role === 'event'" class="flex items-center gap-1.5 text-[11px] text-zinc-500 pl-1">
              <Search :size="11" class="shrink-0" />
              <span>{{ entry.content }}</span>
            </div>
            <!-- Usuário -->
            <div v-else-if="entry.role === 'user'" class="flex justify-end">
              <div class="max-w-[85%] rounded-2xl rounded-br-md px-3.5 py-2 text-sm bg-brand-yellow text-brand-black font-medium whitespace-pre-wrap">
                {{ entry.content }}
              </div>
            </div>
            <!-- Assistente (markdown) -->
            <div v-else class="flex">
              <div
                class="max-w-[85%] rounded-2xl rounded-bl-md px-3.5 py-2 text-sm bg-zinc-800/80 border border-zinc-700/40
                       prose prose-sm prose-invert prose-p:my-1 prose-pre:my-1 prose-pre:text-xs prose-table:text-xs max-w-none overflow-x-auto"
                v-html="md(entry.content)"
              />
            </div>
          </template>

          <!-- Card de confirmação / execução -->
          <div
            v-if="store.pendingAction"
            class="rounded-2xl border-2 border-brand-yellow/70 bg-brand-yellow/10 p-3 text-sm"
          >
            <!-- Executando a ação confirmada -->
            <template v-if="store.executing">
              <p class="font-semibold mb-1 text-brand-yellow flex items-center gap-2">
                <Loader2 :size="15" class="animate-spin" /> Executando…
              </p>
              <p class="text-zinc-200">{{ store.pendingAction.summary }}</p>
              <p class="text-xs text-zinc-400 mt-2">
                Aplicando as alterações no Mercado Livre — o resultado aparece aqui ao terminar.
              </p>
            </template>
            <!-- Aguardando confirmação -->
            <template v-else>
              <p class="font-semibold mb-1 text-brand-yellow">Confirmar ação?</p>
              <p class="mb-2 text-zinc-200">{{ store.pendingAction.summary }}</p>
              <div class="flex gap-2">
                <button
                  @click="store.confirm()"
                  :disabled="store.loading"
                  class="flex items-center gap-1 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold px-3 py-1.5 disabled:opacity-50"
                >
                  <Check :size="14" /> Confirmar
                </button>
                <button
                  @click="store.reject()"
                  :disabled="store.loading"
                  class="rounded-lg bg-zinc-700 hover:bg-zinc-600 text-zinc-200 text-xs font-semibold px-3 py-1.5 disabled:opacity-50"
                >
                  Cancelar
                </button>
              </div>
            </template>
          </div>

          <!-- Pensando (consulta ao modelo) -->
          <div v-if="store.loading && !store.executing" class="flex items-center gap-2 text-xs text-zinc-500">
            <Loader2 :size="14" class="animate-spin" /> pensando e consultando dados…
          </div>
        </div>

        <!-- Input -->
        <div class="px-4 pb-4 pt-2">
          <!-- Miniaturas das imagens anexadas -->
          <div v-if="attachments.length || uploadingImages > 0" class="flex flex-wrap gap-2 mb-2">
            <div
              v-for="(att, i) in attachments"
              :key="att.id"
              class="relative group/att w-14 h-14 rounded-lg overflow-hidden border border-zinc-700"
              :title="att.name"
            >
              <img :src="att.url" :alt="att.name" class="w-full h-full object-cover" />
              <button
                @click="removeAttachment(i)"
                class="absolute top-0.5 right-0.5 p-0.5 rounded-full bg-black/70 opacity-0 group-hover/att:opacity-100 transition-opacity"
                title="Remover"
              >
                <X :size="11" class="text-white" />
              </button>
            </div>
            <div
              v-if="uploadingImages > 0"
              class="w-14 h-14 rounded-lg border border-dashed border-zinc-600 flex items-center justify-center"
            >
              <Loader2 :size="17" class="animate-spin text-zinc-500" />
            </div>
          </div>
          <p v-if="uploadError" class="text-xs text-red-400 mb-2">{{ uploadError }}</p>

          <textarea
            v-model="draft"
            @keydown="onKeydown"
            @paste="onPaste"
            rows="3"
            :maxlength="MAX_CHARS"
            placeholder="O que você quer saber? Pergunte, ou arraste imagens para usar nas fotos…"
            class="w-full px-3 py-2.5 rounded-2xl bg-zinc-800/50 border border-zinc-700/50 outline-none resize-none
                   text-sm leading-relaxed text-zinc-100 placeholder-zinc-500
                   focus:border-brand-yellow/50 transition-colors"
          />
          <div class="flex items-center justify-between mt-2">
            <div class="flex items-center gap-2">
              <button
                @click="fileInput?.click()"
                class="p-2 rounded-lg border border-zinc-700/50 text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800/80 transition-colors"
                title="Anexar imagens (ou arraste/cole no chat)"
              >
                <Paperclip :size="15" />
              </button>
              <input
                ref="fileInput"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                multiple
                class="hidden"
                @change="onPickFiles"
              />
              <div class="text-xs font-medium text-zinc-500">
                <span>{{ charCount }}</span>/<span class="text-zinc-400">{{ MAX_CHARS }}</span>
              </div>
            </div>
            <button
              @click="submit"
              :disabled="store.loading || uploadingImages > 0 || (!draft.trim() && attachments.length === 0)"
              class="group relative p-3 rounded-xl cursor-pointer transition-all duration-300 transform
                     bg-gradient-to-r from-brand-yellow to-brand-yellow-light text-brand-black shadow-lg
                     hover:scale-110 hover:shadow-brand-yellow/40 hover:shadow-xl hover:-rotate-2
                     active:scale-95 disabled:opacity-40 disabled:hover:scale-100 disabled:hover:rotate-0"
              title="Enviar"
            >
              <Send :size="18" class="transition-all duration-300 group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:rotate-12" />
            </button>
          </div>

          <!-- Rodapé -->
          <div class="flex items-center justify-between mt-3 pt-3 border-t border-zinc-800/70 text-xs text-zinc-500 gap-4">
            <div class="flex items-center gap-2 min-w-0">
              <Info :size="12" class="shrink-0" />
              <span class="truncate">
                <kbd class="px-1.5 py-0.5 bg-zinc-800 border border-zinc-600 rounded text-zinc-400 font-mono text-[10px]">Shift + Enter</kbd>
                para nova linha
              </span>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <span class="w-1.5 h-1.5 bg-green-500 rounded-full"></span>
              <span>Operacional</span>
            </div>
          </div>
        </div>

        <!-- Overlay com tons da marca -->
        <div
          class="absolute inset-0 rounded-3xl pointer-events-none"
          style="background: linear-gradient(135deg, rgba(255, 214, 0, 0.14), transparent 45%, rgba(255, 214, 0, 0.1))"
        ></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-fab {
  background: linear-gradient(135deg, rgba(255, 232, 77, 0.55) 0%, rgba(255, 214, 0, 0.45) 60%, rgba(230, 192, 0, 0.4) 100%);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow:
    0 4px 12px rgba(0, 0, 0, 0.2),
    inset 0 1px 1px rgba(255, 255, 255, 0.4);
  border: 1px solid rgba(255, 232, 77, 0.5);
}
.ai-fab:hover {
  transform: scale(1.1) rotate(5deg);
  background: linear-gradient(135deg, rgba(255, 232, 77, 0.7) 0%, rgba(255, 214, 0, 0.6) 60%, rgba(230, 192, 0, 0.55) 100%);
  box-shadow:
    0 4px 14px rgba(0, 0, 0, 0.25),
    inset 0 1px 1px rgba(255, 255, 255, 0.5);
}

.ai-panel {
  animation: popIn 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275) forwards;
}

@keyframes popIn {
  0% {
    opacity: 0;
    transform: scale(0.8) translateY(20px);
  }
  100% {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
</style>

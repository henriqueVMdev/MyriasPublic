import { defineStore } from "pinia";
import { ref, watch } from "vue";
import {
  sendChat,
  confirmAction,
  rejectAction,
  listModels,
  updateModel,
  type PendingAction,
  type OpenRouterModelOption,
} from "@/api/assistant";

export interface ChatEntry {
  role: "user" | "assistant" | "event";
  content: string;
}

const STORAGE_KEY = "hrb-assistant";

function loadSaved(): { entries: ChatEntry[] } {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (raw) {
      const saved = JSON.parse(raw);
      return { entries: Array.isArray(saved.entries) ? saved.entries : [] };
    }
  } catch {
    // estado corrompido → começa limpo
  }
  return { entries: [] };
}

export const useAssistantStore = defineStore("assistant", () => {
  const saved = loadSaved();
  const entries = ref<ChatEntry[]>(saved.entries);
  const model = ref<string | null>(null);
  const models = ref<OpenRouterModelOption[]>([]);
  const modelsLoaded = ref(false);
  const modelSaving = ref(false);
  const pendingAction = ref<PendingAction | null>(null);
  const isOpen = ref(false);
  const loading = ref(false);
  // true só enquanto uma ação confirmada roda no backend (widget mostra "executando")
  const executing = ref(false);

  // Conversa sobrevive à navegação/F5, morre ao fechar a aba (sessionStorage).
  watch(
    entries,
    () => {
      sessionStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ entries: entries.value })
      );
    },
    { deep: true }
  );

  async function ensureModels() {
    if (modelsLoaded.value) return;
    try {
      const data = await listModels();
      models.value = data.models;
      model.value = data.selected;
      modelsLoaded.value = true;
    } catch {
      // sem lista o dropdown fica só com o modelo atual
    }
  }

  async function selectModel(selected: string) {
    if (!selected || selected === model.value || modelSaving.value) return;
    const previous = model.value;
    model.value = selected;
    modelSaving.value = true;
    try {
      const data = await updateModel(selected);
      model.value = data.selected;
    } catch (e) {
      model.value = previous;
      throw e;
    } finally {
      modelSaving.value = false;
    }
  }

  function history() {
    return entries.value
      .filter((e) => e.role === "user" || e.role === "assistant")
      .map((e) => ({ role: e.role as "user" | "assistant", content: e.content }));
  }

  async function send(text: string) {
    const clean = text.trim();
    if (!clean || loading.value) return;
    entries.value.push({ role: "user", content: clean });
    pendingAction.value = null; // nova mensagem invalida a proposta anterior
    loading.value = true;
    try {
      const resp = await sendChat(history());
      for (const ev of resp.tool_events || []) {
        entries.value.push({ role: "event", content: ev });
      }
      entries.value.push({ role: "assistant", content: resp.reply });
      pendingAction.value = resp.pending_action ?? null;
    } catch (e: unknown) {
      const err = e as { response?: { data?: { detail?: string } }; message?: string };
      entries.value.push({
        role: "assistant",
        content:
          "Erro ao falar com o assistente: " +
          (err.response?.data?.detail || err.message || "desconhecido"),
      });
    } finally {
      loading.value = false;
    }
  }

  async function confirm() {
    if (!pendingAction.value || loading.value) return;
    const action = pendingAction.value;
    loading.value = true;
    executing.value = true;
    try {
      const resp = await confirmAction(action.id);
      entries.value.push({
        role: "assistant",
        content:
          "✅ Executado: " +
          action.summary +
          "\n\n```json\n" +
          JSON.stringify(resp.result, null, 2) +
          "\n```",
      });
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { detail?: string } }; message?: string };
      const msg =
        err.response?.status === 410
          ? "A ação expirou — peça de novo ao assistente."
          : err.response?.data?.detail || err.message || "erro desconhecido";
      entries.value.push({ role: "assistant", content: "❌ Não executei: " + msg });
    } finally {
      pendingAction.value = null;
      loading.value = false;
      executing.value = false;
    }
  }

  async function reject() {
    if (!pendingAction.value) return;
    try {
      await rejectAction(pendingAction.value.id);
    } catch {
      // best-effort: expirar sozinha também serve
    }
    entries.value.push({ role: "event", content: "ação cancelada pelo usuário" });
    pendingAction.value = null;
  }

  function clear() {
    entries.value = [];
    pendingAction.value = null;
  }

  return {
    entries,
    model,
    models,
    modelsLoaded,
    modelSaving,
    pendingAction,
    isOpen,
    loading,
    executing,
    ensureModels,
    selectModel,
    send,
    confirm,
    reject,
    clear,
  };
});

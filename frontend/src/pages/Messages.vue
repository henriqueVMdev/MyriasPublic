<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import {
  getThread,
  sendReply,
  type Conversation,
  type Thread,
} from "@/api/messages";
import { useMessagesStore } from "@/stores/messages";
import { useAuthStore } from "@/stores/auth";
import {
  Loader2,
  MessageSquare,
  Send,
  Users,
  RefreshCcw,
  Bell,
  BellOff,
  ArrowLeft,
  Package,
  ExternalLink,
  Check,
} from "lucide-vue-next";

const store = useMessagesStore();
const auth = useAuthStore();

const filterAccount = ref<number | "all">("all");
const selected = ref<Conversation | null>(null);
const thread = ref<Thread | null>(null);
const threadLoading = ref(false);
const replyText = ref("");
const sending = ref(false);
const markingRead = ref(false);
const sendError = ref("");

const ACCOUNT_COLORS = [
  "bg-blue-100 text-blue-700",
  "bg-purple-100 text-purple-700",
  "bg-green-100 text-green-700",
  "bg-orange-100 text-orange-700",
];

function accountColor(userId: number): string {
  const idx = store.accounts.findIndex((a) => a.user_id === userId);
  return ACCOUNT_COLORS[Math.max(0, idx) % ACCOUNT_COLORS.length];
}

const filtered = computed(() => {
  if (filterAccount.value === "all") return store.conversations;
  return store.conversations.filter(
    (c) => c.account.user_id === filterAccount.value
  );
});

const maxMessageLength = computed(
  () => thread.value?.seller_max_message_length || 350
);

const conversationBlocked = computed(() => {
  const status = thread.value?.conversation_status;
  if (typeof status === "string") return status.toLowerCase() === "blocked";
  return status?.status?.toLowerCase() === "blocked";
});

const conversationBlockMessage = computed(() => {
  const status = thread.value?.conversation_status;
  const substatus =
    typeof status === "object" && status ? status.substatus?.toLowerCase() : "";

  if (substatus === "blocked_by_time") {
    return "O prazo permitido pelo Mercado Livre para responder esta venda terminou.";
  }
  if (substatus === "blocked_by_buyer") {
    return "O comprador bloqueou novas mensagens nesta conversa.";
  }
  if (substatus === "blocked_by_fulfillment") {
    return "O Mercado Livre não permite mensagens para esta modalidade de entrega.";
  }
  return "O Mercado Livre bloqueou novas respostas nesta conversa.";
});

function errorMessage(err: any, fallback: string): string {
  const detail = err?.response?.data?.detail;
  if (typeof detail === "string" && detail.trim()) return detail;
  if (typeof detail?.message === "string" && detail.message.trim()) {
    return detail.message;
  }
  return err?.message || fallback;
}

function formatElapsed(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  const diff = Date.now() - d.getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "agora mesmo";
  if (mins < 60) return `${mins} min atrás`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h atrás`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} dia${days > 1 ? "s" : ""} atrás`;
  return d.toLocaleDateString("pt-BR");
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function selectConversation(c: Conversation) {
  selected.value = c;
  thread.value = null;
  replyText.value = "";
  sendError.value = "";
  threadLoading.value = true;
  try {
    // Abrir só lê — não marca como lido no ML nem remove do painel.
    // Isso só acontece quando o usuário clica em "Marcar como lida".
    thread.value = await getThread(c.pack_id, c.account.user_id, false);
  } catch (err: any) {
    alert(
      "Erro ao carregar conversa: " +
        (err?.response?.data?.detail || err?.message || "desconhecido")
    );
  } finally {
    threadLoading.value = false;
  }
}

function backToList() {
  selected.value = null;
  thread.value = null;
}

async function markRead() {
  if (!selected.value) return;
  markingRead.value = true;
  try {
    // mark_read=1 → ML marca como lido agora
    await getThread(selected.value.pack_id, selected.value.account.user_id, true);
    store.removeConversation(selected.value.account.user_id, selected.value.pack_id);
    backToList();
  } catch (err: any) {
    alert(
      "Erro ao marcar como lida: " +
        (err?.response?.data?.detail || err?.message || "desconhecido")
    );
  } finally {
    markingRead.value = false;
  }
}

async function submitReply() {
  if (!auth.can("reply_messages")) return;
  if (!selected.value || !thread.value) return;
  const text = replyText.value.trim();
  if (!text) return;
  if (conversationBlocked.value) {
    sendError.value = conversationBlockMessage.value;
    return;
  }
  if (text.length > maxMessageLength.value) {
    sendError.value = `A mensagem ultrapassa o limite de ${maxMessageLength.value} caracteres.`;
    return;
  }
  sendError.value = "";
  sending.value = true;
  try {
    await sendReply(
      selected.value.pack_id,
      selected.value.account.user_id,
      text
    );
    replyText.value = "";
    // Recarrega a thread pra mostrar a mensagem enviada
    thread.value = await getThread(
      selected.value.pack_id,
      selected.value.account.user_id
    );
  } catch (err: any) {
    sendError.value = errorMessage(err, "Não foi possível enviar a mensagem.");
  } finally {
    sending.value = false;
  }
}

async function enableNotifications() {
  await store.requestNotificationPermission();
}

onMounted(() => {
  // Enquanto a tela está aberta, o polling busca a versão enriquecida
  // (comprador + itens). Ao sair, volta ao polling leve (só badge).
  store.enrich = true;
  store.fetchNow(true);
});

onUnmounted(() => {
  store.enrich = false;
});
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6 gap-3 flex-wrap">
      <div>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Mensagens pós-venda</h2>
        <p class="text-sm text-gray-500 mt-1">
          {{ store.unreadTotal }} não lida(s) em {{ store.accounts.length }} conta(s)
        </p>
      </div>

      <div class="flex items-center gap-2">
        <button
          @click="enableNotifications"
          class="px-3.5 py-2 text-sm font-medium bg-white border rounded-xl hover:bg-gray-50 hover:border-gray-300 flex items-center gap-2 transition-colors shadow-sm"
          :class="store.notificationsEnabled ? 'text-green-600 border-green-300' : 'text-gray-700'"
        >
          <Bell v-if="store.notificationsEnabled" :size="14" />
          <BellOff v-else :size="14" />
          {{ store.notificationsEnabled ? "Notificações ativas" : "Ativar notificações" }}
        </button>
        <button
          @click="store.fetchNow(true)"
          :disabled="store.loading"
          class="px-3.5 py-2 text-sm font-medium bg-white border rounded-xl hover:bg-gray-50 hover:border-gray-300 flex items-center gap-2 disabled:opacity-50 transition-colors shadow-sm"
        >
          <RefreshCcw v-if="!store.loading" :size="14" />
          <Loader2 v-else :size="14" class="animate-spin" />
          Atualizar
        </button>
      </div>
    </div>

    <p v-if="store.lastFetched" class="text-[11px] text-gray-400 mb-3">
      Atualização automática a cada 30s &mdash; última:
      {{ store.lastFetched.toLocaleTimeString("pt-BR") }}
    </p>

    <!-- Filtros (só visíveis na lista) -->
    <div
      v-if="!selected"
      class="bg-white rounded-2xl border shadow-sm p-4 mb-4 flex flex-wrap gap-3 items-center"
    >
      <div class="flex items-center gap-2 text-xs">
        <Users :size="14" class="text-gray-400" />
        <button
          type="button"
          @click="filterAccount = 'all'"
          class="px-2.5 py-1 rounded-full border transition-colors"
          :class="filterAccount === 'all' ? 'bg-gray-900 text-white border-gray-900' : 'bg-white border-gray-300 hover:bg-gray-50'"
        >
          Todas ({{ store.unreadTotal }})
        </button>
        <button
          v-for="a in store.accounts"
          :key="a.user_id"
          type="button"
          @click="filterAccount = a.user_id"
          class="px-2.5 py-1 rounded-full border transition-colors"
          :class="filterAccount === a.user_id ? 'bg-gray-900 text-white border-gray-900' : 'bg-white border-gray-300 hover:bg-gray-50'"
        >
          {{ a.nickname }} ({{ store.counts[a.user_id] || 0 }})
        </button>
      </div>
    </div>

    <!-- Lista de conversas -->
    <div v-if="!selected">
      <div v-if="store.loading && store.conversations.length === 0" class="flex items-center justify-center py-12">
        <Loader2 :size="32" class="animate-spin text-meli-blue" />
      </div>

      <div
        v-else-if="filtered.length === 0"
        class="bg-white rounded-2xl border shadow-sm p-10 text-center text-gray-500"
      >
        <MessageSquare :size="40" class="mx-auto mb-2 text-gray-300" />
        Nenhuma mensagem não lida.
      </div>

      <div v-else class="space-y-2 stagger-children">
        <div
          v-for="c in filtered"
          :key="`${c.account.user_id}-${c.pack_id}`"
          class="bg-white rounded-2xl border shadow-sm p-4 hover:shadow-md hover:border-gray-300 transition-all cursor-pointer"
          @click="selectConversation(c)"
        >
          <!-- Topo: conta + comprador + data -->
          <div class="flex items-center gap-2 flex-wrap">
            <span
              class="text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full"
              :class="accountColor(c.account.user_id)"
            >
              {{ c.account.nickname }}
            </span>
            <span v-if="c.buyer_nickname" class="text-xs font-semibold text-gray-700">
              {{ c.buyer_nickname }}
            </span>
            <span class="text-xs text-gray-400">{{ formatElapsed(c.last_date) }}</span>
            <span class="text-xs text-gray-400 font-mono ml-auto">Venda #{{ c.pack_id }}</span>
          </div>

          <!-- Itens comprados (acima da mensagem) -->
          <div v-if="c.items && c.items.length" class="mt-3 space-y-2">
            <div
              v-for="(it, i) in c.items"
              :key="i"
              class="flex items-center gap-2.5"
            >
              <a
                v-if="it.permalink"
                :href="it.permalink"
                target="_blank"
                @click.stop
                class="flex-shrink-0"
                title="Abrir anúncio"
              >
                <img
                  v-if="it.thumbnail"
                  :src="it.thumbnail"
                  class="w-11 h-11 rounded-lg object-cover border hover:opacity-80 transition-opacity"
                />
                <div v-else class="w-11 h-11 rounded-lg bg-gray-100 flex items-center justify-center">
                  <Package :size="16" class="text-gray-300" />
                </div>
              </a>
              <div v-else class="w-11 h-11 rounded-lg bg-gray-100 flex items-center justify-center flex-shrink-0">
                <Package :size="16" class="text-gray-300" />
              </div>

              <div class="min-w-0 flex-1">
                <p class="text-xs text-gray-800 truncate" :title="it.title">{{ it.title }}</p>
                <p class="text-[11px] text-gray-400">
                  <span v-if="it.sku">SKU: <span class="font-mono text-gray-500">{{ it.sku }}</span></span>
                  <span v-else>sem SKU</span>
                  <span v-if="it.quantity > 1"> &middot; {{ it.quantity }} un.</span>
                </p>
              </div>

              <a
                v-if="it.permalink"
                :href="it.permalink"
                target="_blank"
                @click.stop
                class="p-1.5 rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-700 flex-shrink-0 transition-colors"
                title="Ir para o item comprado"
              >
                <ExternalLink :size="15" />
              </a>
            </div>
          </div>

          <!-- Mensagem do cliente + contagem de não-lidas (bolinha amarela à direita) -->
          <div class="mt-3 pt-3 border-t flex items-start gap-2">
            <p class="flex-1 min-w-0 text-sm text-gray-800 line-clamp-2 whitespace-pre-wrap">
              {{ c.last_text || "(sem texto)" }}
            </p>
            <span
              v-if="c.unread_count > 0"
              class="flex-shrink-0 mt-0.5 inline-flex items-center justify-center min-w-[1.25rem] h-5 px-1.5 rounded-full bg-brand-yellow text-brand-black text-[11px] font-bold shadow-sm"
              :title="`${c.unread_count} mensagem(ns) não lida(s)`"
            >
              {{ c.unread_count }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Thread selecionada -->
    <div v-else class="bg-white rounded-2xl border shadow-sm animate-fade-up">
      <div class="p-4 border-b flex items-center gap-3">
        <button
          type="button"
          @click="backToList"
          class="p-2 rounded-lg hover:bg-gray-100 transition-colors"
        >
          <ArrowLeft :size="18" />
        </button>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2 flex-wrap">
            <span
              class="text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full"
              :class="accountColor(selected.account.user_id)"
            >
              {{ selected.account.nickname }}
            </span>
            <span class="text-xs text-gray-400 font-mono">Venda #{{ selected.pack_id }}</span>
          </div>
          <p class="text-xs text-gray-500 mt-0.5">
            Comprador: {{ selected.buyer_nickname || thread?.buyer_id || selected.buyer_id || "—" }}
          </p>
        </div>
        <button
          type="button"
          @click="markRead"
          :disabled="markingRead || threadLoading"
          title="Marca como lida no Mercado Livre e remove do painel"
          class="flex-shrink-0 px-3.5 py-2 text-sm font-medium bg-brand-yellow text-brand-black rounded-xl hover:brightness-95 flex items-center gap-2 disabled:opacity-50 transition-all shadow-sm"
        >
          <Loader2 v-if="markingRead" :size="14" class="animate-spin" />
          <Check v-else :size="14" />
          Marcar como lida
        </button>
      </div>

      <div class="p-4 max-h-[60vh] overflow-y-auto space-y-3">
        <div v-if="threadLoading" class="flex items-center justify-center py-8">
          <Loader2 :size="24" class="animate-spin text-meli-blue" />
        </div>
        <div v-else-if="thread?.error" class="text-sm text-red-600 text-center py-4">
          {{ thread.error }}
        </div>
        <div v-else-if="!thread || thread.messages.length === 0" class="text-sm text-gray-500 text-center py-4">
          Sem mensagens nesta conversa.
        </div>
        <div
          v-for="m in thread?.messages || []"
          :key="m.id"
          class="flex"
          :class="m.is_seller ? 'justify-end' : 'justify-start'"
        >
          <div
            class="max-w-[75%] px-3.5 py-2.5 text-sm whitespace-pre-wrap shadow-sm"
            :class="m.is_seller
              ? 'bg-brand-yellow text-brand-black rounded-2xl rounded-br-md'
              : 'bg-gray-100 text-gray-900 rounded-2xl rounded-bl-md'"
          >
            <p>{{ m.text }}</p>
            <p
              class="text-[10px] mt-1"
              :class="m.is_seller ? 'text-black/45' : 'text-gray-500'"
            >
              {{ formatDateTime(m.date) }}
            </p>
          </div>
        </div>
      </div>

      <div class="p-4 border-t space-y-2">
        <div
          v-if="conversationBlocked"
          class="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
        >
          {{ conversationBlockMessage }}
        </div>
        <div
          v-if="sendError"
          class="rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {{ sendError }}
        </div>
        <textarea
          v-model="replyText"
          @input="sendError = ''"
          rows="3"
          :placeholder="auth.can('reply_messages') ? 'Digite sua resposta...' : 'Você não tem permissão para responder mensagens'"
          :maxlength="maxMessageLength"
          class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue resize-y
                 disabled:bg-gray-100 disabled:cursor-not-allowed dark:disabled:bg-zinc-800"
          :disabled="sending || conversationBlocked || !auth.can('reply_messages')"
        ></textarea>
        <div class="flex items-center justify-between gap-2">
          <span class="text-xs text-gray-400">
            {{ replyText.length }}/{{ maxMessageLength }}
          </span>
          <button
            type="button"
            @click="submitReply"
            :disabled="sending || conversationBlocked || !replyText.trim() || threadLoading || !auth.can('reply_messages')"
            :title="!auth.can('reply_messages') ? 'Você não tem permissão para responder mensagens' : undefined"
            class="px-5 py-2 bg-meli-blue text-brand-yellow font-semibold rounded-xl text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Loader2 v-if="sending" :size="14" class="animate-spin" />
            <Send v-else :size="14" />
            Enviar
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

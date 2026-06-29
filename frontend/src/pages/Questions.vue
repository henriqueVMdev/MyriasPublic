<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import {
  listQuestions,
  answerQuestion,
  getQuestionStats,
  type Question,
  type QuestionStats,
} from "@/api/questions";
import { useQuestionsStore } from "@/stores/questions";
import { useAuthStore } from "@/stores/auth";
import QuestionsMiniChart from "@/components/QuestionsMiniChart.vue";
import {
  Loader2,
  MessageCircle,
  ExternalLink,
  Send,
  Check,
  Users,
  RefreshCcw,
  Bell,
  BellOff,
  ChevronDown,
  ChevronUp,
  History,
  EyeOff,
  ArrowDownWideNarrow,
  ArrowUpNarrowWide,
} from "lucide-vue-next";
import type { QuestionHistory } from "@/api/questions";

const store = useQuestionsStore();
const auth = useAuthStore();

const status = ref<"UNANSWERED" | "ANSWERED">("UNANSWERED");
// Ordenação da aba "Respondidas": mais recentes ou menos recentes primeiro
const answeredSort = ref<"recent" | "oldest">("recent");
const filterAccount = ref<number | "all">("all");
const replyingId = ref<number | null>(null);
const replyText = ref<Record<number, string>>({});
const sending = ref<number | null>(null);

// Perguntas respondidas ficam em estado local (não entram no polling global)
const answered = ref<Question[]>([]);
const answeredLoading = ref(false);
const answeredAccounts = ref<Array<{ user_id: number; nickname: string }>>([]);
const answeredCounts = ref<Record<string, number>>({});

// Para UNANSWERED vêm do store; para ANSWERED, do fetch local
const accounts = computed(() =>
  status.value === "UNANSWERED" ? store.accountsList : answeredAccounts.value
);
const counts = computed(() =>
  status.value === "UNANSWERED" ? store.accountCounts : answeredCounts.value
);

// Estatísticas / gráficos
const statsPeriod = ref<"day" | "month">("day");
const stats = ref<QuestionStats | null>(null);
const statsLoading = ref(false);
const ACCOUNT_CHART_COLORS = ["#3483FA", "#9333EA", "#059669", "#EA580C"];

async function loadStats() {
  statsLoading.value = true;
  try {
    const periods = statsPeriod.value === "day" ? 30 : 6;
    stats.value = await getQuestionStats(statsPeriod.value, periods);
  } catch (err) {
    console.error("Erro ao carregar stats:", err);
  } finally {
    statsLoading.value = false;
  }
}

function changeStatsPeriod(p: "day" | "month") {
  statsPeriod.value = p;
  loadStats();
}

async function load() {
  if (status.value === "UNANSWERED") {
    // Limpa antes de buscar — garante que pergunta obsoleta não fique na tela
    // caso o fetch anterior tenha sido silenciosamente engolido por um erro.
    store.clearQuestions();
    await store.fetchNow(true);
    return;
  }
  answered.value = [];
  answeredLoading.value = true;
  try {
    const resp = await listQuestions("ANSWERED");
    answered.value = resp.questions;
    answeredAccounts.value = resp.accounts;
    answeredCounts.value = resp.counts || {};
  } catch (err) {
    console.error("Erro ao carregar perguntas respondidas:", err);
  } finally {
    answeredLoading.value = false;
  }
}

async function enableNotifications() {
  if (!("Notification" in window)) {
    alert("Seu navegador não suporta notificações.");
    return;
  }
  if (Notification.permission === "denied") {
    alert(
      "Notificações estão bloqueadas no navegador. " +
        "Para ativar, abra as configurações do site (cadeado na barra de endereço) " +
        "e permita notificações."
    );
    return;
  }
  const granted = await store.requestNotificationPermission();
  if (!granted) {
    alert("Permissão de notificação não concedida.");
  }
}

// Snapshot exibido das perguntas sem resposta. Espelha o store, exceto enquanto
// uma caixa de resposta está aberta — aí congela pra não reordenar/sumir cards
// embaixo de quem está digitando. O store segue atualizando no fundo (e o badge
// da sidebar, que lê store.questions direto, fica ao vivo).
const displayQuestions = ref<Question[]>([]);
watch(
  [() => store.questions, replyingId],
  () => {
    if (replyingId.value === null) displayQuestions.value = store.questions;
  },
  { immediate: true }
);

const questions = computed(() =>
  status.value === "UNANSWERED" ? displayQuestions.value : answered.value
);
const loading = computed(() =>
  status.value === "UNANSWERED" ? store.loading : answeredLoading.value
);

const filtered = computed(() => {
  if (filterAccount.value === "all") return questions.value;
  return questions.value.filter((q) => q.account.user_id === filterAccount.value);
});

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

function toggleReply(q: Question) {
  if (!auth.can("reply_questions")) return;
  replyingId.value = replyingId.value === q.id ? null : q.id;
  if (!replyText.value[q.id]) replyText.value[q.id] = "";
}

async function submitAnswer(q: Question) {
  const text = (replyText.value[q.id] || "").trim();
  if (!text) return;
  sending.value = q.id;
  try {
    await answerQuestion(q.id, text, q.account.user_id, {
      itemId: q.item?.id ?? null,
      questionText: q.text ?? null,
    });
    // Remove imediatamente da lista — o tombstone no store evita que volte
    // enquanto o ML ainda reporta a pergunta como UNANSWERED (eventual consistency).
    // Remover ANTES de fechar a caixa: ao zerar replyingId o snapshot ressincroniza
    // já sem a pergunta recém-respondida.
    store.removeQuestion(q.id);
    replyText.value[q.id] = "";
    replyingId.value = null;
    store.fetchNow(true).catch(() => {});
  } catch (err: any) {
    alert(
      "Erro ao responder: " +
        (err?.response?.data?.detail || err?.message || "desconhecido")
    );
  } finally {
    sending.value = null;
  }
}

// Paleta de cores para distinguir contas
const ACCOUNT_COLORS = [
  "bg-blue-100 text-blue-700",
  "bg-purple-100 text-purple-700",
  "bg-green-100 text-green-700",
  "bg-orange-100 text-orange-700",
];
const accountColorMap = computed<Map<number, string>>(() => {
  const m = new Map<number, string>();
  accounts.value.forEach((a, i) => {
    m.set(a.user_id, ACCOUNT_COLORS[i % ACCOUNT_COLORS.length]);
  });
  return m;
});
function accountColor(userId: number): string {
  return accountColorMap.value.get(userId) ?? ACCOUNT_COLORS[0];
}

// ---- Agrupamento por (comprador + anúncio) — UNANSWERED e ANSWERED ----

const expandedGroups = ref<Set<string>>(new Set());
const expandedHistory = ref<Set<string>>(new Set());

function toggleHistory(key: string) {
  const next = new Set(expandedHistory.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedHistory.value = next;
}

function groupKey(q: Question): string {
  const buyer = String(q.buyer.id ?? q.buyer.nickname ?? "unknown");
  const item = String(q.item.id ?? "noitem");
  return `${buyer}__${item}`;
}

function toggleGroup(key: string) {
  const next = new Set(expandedGroups.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedGroups.value = next;
}

interface AnsweredGroup {
  key: string;
  buyerNickname: string;
  item: Question["item"];
  accountUserId: number;
  accountNickname: string;
  questions: Question[]; // ordenadas mais recente primeiro
}

interface UnansweredGroup {
  key: string;
  buyerNickname: string;
  item: Question["item"];
  accountUserId: number;
  accountNickname: string;
  questions: Question[];
  history: QuestionHistory[];
}

const groupedUnanswered = computed((): UnansweredGroup[] => {
  const map = new Map<string, UnansweredGroup>();
  for (const q of filtered.value) {
    const key = groupKey(q);
    if (!map.has(key)) {
      map.set(key, {
        key,
        buyerNickname: q.buyer.nickname || `Comprador #${q.buyer.id ?? "?"}`,
        item: q.item,
        accountUserId: q.account.user_id,
        accountNickname: q.account.nickname,
        questions: [],
        history: q.history || [],
      });
    }
    map.get(key)!.questions.push(q);
  }
  const groups = Array.from(map.values());
  for (const g of groups) {
    g.questions.sort((a, b) =>
      (b.date_created ?? "").localeCompare(a.date_created ?? "")
    );
  }
  groups.sort((a, b) =>
    (b.questions[0]?.date_created ?? "").localeCompare(
      a.questions[0]?.date_created ?? ""
    )
  );
  return groups;
});

const groupedAnswered = computed((): AnsweredGroup[] => {
  const map = new Map<string, AnsweredGroup>();
  for (const q of filtered.value) {
    const key = groupKey(q);
    if (!map.has(key)) {
      map.set(key, {
        key,
        buyerNickname: q.buyer.nickname || `Comprador #${q.buyer.id ?? "?"}`,
        item: q.item,
        accountUserId: q.account.user_id,
        accountNickname: q.account.nickname,
        questions: [],
      });
    }
    map.get(key)!.questions.push(q);
  }
  const groups = Array.from(map.values());
  // Dentro de cada grupo, mais recente primeiro
  for (const g of groups) {
    g.questions.sort((a, b) =>
      (b.date_created ?? "").localeCompare(a.date_created ?? "")
    );
  }
  // Ordena grupos pela atividade mais recente, respeitando o seletor.
  // Dentro do grupo a pergunta mais nova é a questions[0] (ordenado acima).
  groups.sort((a, b) => {
    const cmp = (a.questions[0]?.date_created ?? "").localeCompare(
      b.questions[0]?.date_created ?? ""
    );
    return answeredSort.value === "recent" ? -cmp : cmp;
  });
  return groups;
});

onMounted(() => {
  load();
  loadStats();
  store.requestNotificationPermission();
});
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6 gap-3 flex-wrap">
      <div>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Perguntas</h2>
        <p class="text-sm text-gray-500 mt-1">
          <template v-if="filterAccount === 'all'">
            {{ questions.length }} {{ status === "UNANSWERED" ? "sem resposta" : "respondidas" }}
            em {{ accounts.length }} conta(s)<template v-if="status === 'ANSWERED'"> · última semana</template>
          </template>
          <template v-else>
            {{ filtered.length }} de {{ questions.length }}
            {{ status === "UNANSWERED" ? "sem resposta" : "respondidas" }}
            (conta filtrada)
          </template>
        </p>
      </div>

      <div class="flex items-center gap-2">
        <button
          @click="enableNotifications"
          class="px-3.5 py-2 text-sm font-medium bg-white border rounded-xl hover:bg-gray-50 hover:border-gray-300 flex items-center gap-2 transition-colors shadow-sm"
          :class="store.notificationsEnabled ? 'text-green-600 border-green-300' : 'text-gray-700'"
          :title="store.notificationsEnabled
            ? 'Notificações ativas'
            : 'Clique para receber notificações do navegador'"
        >
          <Bell v-if="store.notificationsEnabled" :size="14" />
          <BellOff v-else :size="14" />
          {{ store.notificationsEnabled ? "Notificações ativas" : "Ativar notificações" }}
        </button>
        <button
          @click="load"
          :disabled="loading"
          class="px-3.5 py-2 text-sm font-medium bg-white border rounded-xl hover:bg-gray-50 hover:border-gray-300 flex items-center gap-2 disabled:opacity-50 transition-colors shadow-sm"
        >
          <RefreshCcw v-if="!loading" :size="14" />
          <Loader2 v-else :size="14" class="animate-spin" />
          Atualizar
        </button>
      </div>
    </div>

    <p v-if="store.lastFetched" class="text-[11px] text-gray-400 mb-3">
      Atualização automática a cada 30s &mdash; última:
      {{ store.lastFetched.toLocaleTimeString("pt-BR") }}
    </p>

    <!-- Filtros -->
    <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4 flex flex-wrap gap-3 items-center">
      <div class="flex items-center gap-1 p-1 bg-gray-100 rounded-xl">
        <button
          type="button"
          @click="status = 'UNANSWERED'; replyingId = null; load()"
          class="px-3 py-1.5 rounded-md text-xs font-medium transition-colors"
          :class="status === 'UNANSWERED' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
        >
          Sem resposta
        </button>
        <button
          type="button"
          @click="status = 'ANSWERED'; replyingId = null; load()"
          class="px-3 py-1.5 rounded-md text-xs font-medium transition-colors"
          :class="status === 'ANSWERED' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
        >
          Respondidas
        </button>
      </div>

      <div class="flex items-center gap-2 text-xs">
        <Users :size="14" class="text-gray-400" />
        <button
          type="button"
          @click="filterAccount = 'all'"
          class="px-2.5 py-1 rounded-full border transition-colors"
          :class="filterAccount === 'all' ? 'bg-gray-900 text-white border-gray-900' : 'bg-white border-gray-300 hover:bg-gray-50'"
        >
          Todas ({{ questions.length }})
        </button>
        <button
          v-for="a in accounts"
          :key="a.user_id"
          type="button"
          @click="filterAccount = a.user_id"
          class="px-2.5 py-1 rounded-full border transition-colors"
          :class="filterAccount === a.user_id ? 'bg-gray-900 text-white border-gray-900' : 'bg-white border-gray-300 hover:bg-gray-50'"
        >
          {{ a.nickname }} ({{ counts[a.user_id] || 0 }})
        </button>
      </div>
    </div>

    <!-- Loading (só 1ª carga / refresh manual — polling de fundo não apaga a lista) -->
    <div v-if="loading && questions.length === 0" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <!-- Empty -->
    <div
      v-else-if="filtered.length === 0"
      class="bg-white rounded-2xl border shadow-sm p-10 text-center text-gray-500"
    >
      <MessageCircle :size="40" class="mx-auto mb-2 text-gray-300" />
      {{ status === "UNANSWERED" ? "Nenhuma pergunta sem resposta." : "Nenhuma pergunta respondida no período." }}
    </div>

    <!-- UNANSWERED: agrupado por comprador + anúncio -->
    <div v-else-if="status === 'UNANSWERED'" class="space-y-3">
      <div
        v-for="group in groupedUnanswered"
        :key="group.key"
        class="bg-white rounded-2xl border shadow-sm overflow-hidden hover:shadow-md transition-shadow"
      >
        <!-- Cabeçalho: foto + título + comprador -->
        <div class="flex gap-4 p-4 border-b border-gray-100">
          <a :href="group.item.permalink || '#'" target="_blank" class="flex-shrink-0">
            <img
              v-if="group.item.thumbnail"
              :src="group.item.thumbnail"
              :alt="group.item.title || ''"
              class="w-16 h-16 rounded-lg object-cover bg-gray-100 hover:opacity-80 transition-opacity"
            />
            <div v-else class="w-16 h-16 rounded-lg bg-gray-100 flex items-center justify-center">
              <MessageCircle :size="18" class="text-gray-300" />
            </div>
          </a>
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 flex-wrap">
              <span
                class="text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full flex-shrink-0"
                :class="accountColor(group.accountUserId)"
              >
                {{ group.accountNickname }}
              </span>
              <span class="text-sm font-medium text-gray-900 truncate">
                {{ group.item.title || group.item.id }}
              </span>
              <a
                v-if="group.item.permalink"
                :href="group.item.permalink"
                target="_blank"
                class="text-meli-blue flex-shrink-0"
              >
                <ExternalLink :size="11" />
              </a>
            </div>
            <div class="text-xs text-gray-400 flex items-center gap-2 mt-0.5">
              <span v-if="group.item.sku">SKU: <span class="font-mono">{{ group.item.sku }}</span></span>
              <span v-if="group.item.id" class="font-mono">{{ group.item.id }}</span>
            </div>
            <div class="text-xs font-semibold text-gray-700 mt-1">
              {{ group.buyerNickname }}
              <span class="font-normal text-gray-400">· {{ group.questions.length }} pergunta{{ group.questions.length !== 1 ? "s" : "" }} sem resposta</span>
            </div>
          </div>
        </div>

        <!-- Perguntas sem resposta -->
        <div class="divide-y divide-gray-100">
          <div
            v-for="q in group.questions"
            :key="q.id"
            class="px-4 py-3"
          >
            <div class="flex items-center gap-2 text-xs text-gray-400 mb-2">
              {{ formatElapsed(q.date_created) }}
              <button
                type="button"
                @click="store.dismissQuestion(q.id)"
                title="Ocultar permanentemente (já respondida / travada)"
                class="ml-auto flex items-center gap-1 text-gray-300 hover:text-red-400 transition-colors"
              >
                <EyeOff :size="13" />
                <span class="text-[10px]">ocultar</span>
              </button>
            </div>
            <div class="p-3 bg-gray-50 rounded-lg">
              <p class="text-sm text-gray-900 whitespace-pre-wrap">{{ q.text }}</p>
            </div>
            <div class="mt-2">
              <button
                v-if="replyingId !== q.id"
                type="button"
                @click="toggleReply(q)"
                :disabled="!auth.can('reply_questions')"
                :title="!auth.can('reply_questions') ? 'Você não tem permissão para responder perguntas' : undefined"
                class="text-sm text-meli-blue hover:underline flex items-center gap-1
                       disabled:opacity-40 disabled:cursor-not-allowed disabled:no-underline disabled:text-gray-400"
              >
                <Send :size="14" />
                Responder
              </button>
              <div v-else class="space-y-2">
                <textarea
                  v-model="replyText[q.id]"
                  rows="3"
                  placeholder="Digite a resposta..."
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue resize-y"
                  :disabled="sending === q.id"
                ></textarea>
                <div class="flex gap-2">
                  <button
                    type="button"
                    @click="submitAnswer(q)"
                    :disabled="sending === q.id || !(replyText[q.id] || '').trim()"
                    class="px-4 py-1.5 bg-meli-blue text-brand-yellow font-semibold rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1 disabled:opacity-50"
                  >
                    <Loader2 v-if="sending === q.id" :size="14" class="animate-spin" />
                    <Send v-else :size="14" />
                    Enviar
                  </button>
                  <button
                    type="button"
                    @click="toggleReply(q)"
                    :disabled="sending === q.id"
                    class="px-3 py-1.5 text-gray-600 rounded-lg text-sm hover:bg-gray-100 transition-colors"
                  >
                    Cancelar
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Histórico de perguntas respondidas -->
        <div v-if="group.history.length > 0" class="border-t border-gray-100">
          <button
            type="button"
            @click="toggleHistory(group.key)"
            class="w-full flex items-center gap-2 px-4 py-2.5 text-xs text-gray-500 hover:bg-gray-50 transition-colors"
          >
            <History :size="13" />
            <span>{{ group.history.length }} pergunta{{ group.history.length !== 1 ? "s" : "" }} anterior{{ group.history.length !== 1 ? "es" : "" }}</span>
            <ChevronDown v-if="!expandedHistory.has(group.key)" :size="13" class="ml-auto" />
            <ChevronUp v-else :size="13" class="ml-auto" />
          </button>
          <div v-if="expandedHistory.has(group.key)" class="px-4 pb-3 space-y-3">
            <div
              v-for="hq in group.history"
              :key="hq.id"
              class="space-y-1.5"
            >
              <div class="flex items-center gap-2 text-xs text-gray-400">
                {{ formatElapsed(hq.date_created) }}
              </div>
              <div class="p-2.5 bg-gray-50 rounded-lg">
                <p class="text-sm text-gray-700 whitespace-pre-wrap">{{ hq.text }}</p>
              </div>
              <div v-if="hq.answer?.text" class="p-2.5 bg-brand-yellow-soft/60 dark:bg-zinc-800/60 rounded-lg border-l-4 border-brand-yellow">
                <div class="text-xs text-gray-400 mb-1 flex items-center gap-1">
                  <Check :size="11" class="text-meli-blue" />
                  Respondido {{ formatElapsed(hq.answer.date_created || null) }}
                </div>
                <p class="text-sm text-gray-700 whitespace-pre-wrap">{{ hq.answer.text }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ANSWERED: agrupado por comprador + anúncio -->
    <div v-else class="space-y-3">
      <!-- Ordenação -->
      <div class="flex items-center justify-end">
        <div class="flex items-center gap-1 p-1 bg-gray-100 rounded-xl">
          <button
            type="button"
            @click="answeredSort = 'recent'"
            class="px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5"
            :class="answeredSort === 'recent' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
          >
            <ArrowDownWideNarrow :size="13" /> Mais recentes
          </button>
          <button
            type="button"
            @click="answeredSort = 'oldest'"
            class="px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5"
            :class="answeredSort === 'oldest' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
          >
            <ArrowUpNarrowWide :size="13" /> Menos recentes
          </button>
        </div>
      </div>

      <div
        v-for="group in groupedAnswered"
        :key="group.key"
        class="bg-white rounded-2xl border shadow-sm overflow-hidden hover:shadow-md transition-shadow"
      >
        <!-- Cabeçalho: foto do anúncio + título + comprador + preview -->
        <button
          type="button"
          class="w-full flex items-center gap-3 px-4 py-3 hover:bg-gray-50 transition-colors text-left"
          @click="toggleGroup(group.key)"
        >
          <!-- Foto do anúncio -->
          <a
            :href="group.item.permalink || '#'"
            target="_blank"
            class="flex-shrink-0"
            @click.stop
          >
            <img
              v-if="group.item.thumbnail"
              :src="group.item.thumbnail"
              :alt="group.item.title || ''"
              class="w-14 h-14 rounded-lg object-cover bg-gray-100 hover:opacity-80 transition-opacity"
            />
            <div v-else class="w-14 h-14 rounded-lg bg-gray-100 flex items-center justify-center">
              <MessageCircle :size="20" class="text-gray-300" />
            </div>
          </a>

          <!-- Info -->
          <div class="flex-1 min-w-0">
            <!-- Título do anúncio -->
            <div class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-medium text-gray-900 truncate">
                {{ group.item.title || group.item.id }}
              </span>
              <a
                v-if="group.item.permalink"
                :href="group.item.permalink"
                target="_blank"
                class="text-meli-blue flex-shrink-0"
                @click.stop
              >
                <ExternalLink :size="11" />
              </a>
            </div>

            <!-- Comprador + conta + contagem -->
            <div class="flex items-center gap-2 mt-0.5 flex-wrap">
              <span class="text-xs font-semibold text-gray-700">{{ group.buyerNickname }}</span>
              <span
                class="text-[10px] uppercase font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0"
                :class="accountColor(group.accountUserId)"
              >
                {{ group.accountNickname }}
              </span>
              <span class="text-[11px] bg-gray-100 text-gray-500 px-2 py-0.5 rounded-full flex-shrink-0">
                {{ group.questions.length }} pergunta{{ group.questions.length !== 1 ? "s" : "" }}
              </span>
            </div>

            <!-- Preview da última pergunta (só colapsado) -->
            <p v-if="!expandedGroups.has(group.key)" class="text-xs text-gray-400 truncate mt-1 italic">
              "{{ group.questions[0]?.text }}"
            </p>
          </div>

          <ChevronDown v-if="!expandedGroups.has(group.key)" :size="16" class="text-gray-400 flex-shrink-0" />
          <ChevronUp v-else :size="16" class="text-gray-400 flex-shrink-0" />
        </button>

        <!-- Perguntas expandidas -->
        <div v-if="expandedGroups.has(group.key)" class="divide-y divide-gray-100 border-t border-gray-100">
          <div v-for="q in group.questions" :key="q.id" class="px-4 py-3 space-y-2">
            <div class="flex items-center gap-2 text-xs text-gray-500">
              <span class="font-medium text-gray-700">{{ group.buyerNickname }}</span>
              <span class="text-gray-300">·</span>
              <span>{{ formatElapsed(q.date_created) }}</span>
            </div>
            <!-- Pergunta -->
            <div class="p-2.5 bg-gray-50 rounded-lg">
              <p class="text-sm text-gray-900 whitespace-pre-wrap">{{ q.text }}</p>
            </div>
            <!-- Resposta -->
            <div v-if="q.answer?.text" class="p-2.5 bg-brand-yellow-soft/60 dark:bg-zinc-800/60 rounded-lg border-l-4 border-brand-yellow">
              <div class="text-xs text-gray-500 mb-1 flex items-center gap-1">
                <Check :size="12" class="text-meli-blue" />
                Respondido {{ formatElapsed(q.answer.date_created || null) }}
              </div>
              <p class="text-sm text-gray-800 whitespace-pre-wrap">{{ q.answer.text }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Gráficos de volume -->
    <div class="bg-white rounded-2xl border shadow-sm p-4 mt-4">
      <div class="flex items-center justify-between mb-3 gap-2 flex-wrap">
        <h3 class="font-bold text-sm">
          Volume de perguntas
          <span class="text-gray-500 font-normal">
            ({{ statsPeriod === "day" ? "últimos 30 dias" : "últimos 6 meses" }})
          </span>
        </h3>
        <div class="flex items-center gap-1 p-1 bg-gray-100 rounded-xl">
          <button
            type="button"
            @click="changeStatsPeriod('day')"
            class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
            :class="statsPeriod === 'day' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
          >
            Por dia
          </button>
          <button
            type="button"
            @click="changeStatsPeriod('month')"
            class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
            :class="statsPeriod === 'month' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
          >
            Por mês
          </button>
        </div>
      </div>

      <div v-if="statsLoading" class="flex items-center justify-center py-6 text-gray-400">
        <Loader2 :size="20" class="animate-spin" />
      </div>
      <div
        v-else-if="!stats || stats.accounts.length === 0"
        class="text-sm text-gray-500 text-center py-4"
      >
        Sem dados de perguntas no período.
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
        <QuestionsMiniChart
          v-for="(acc, idx) in stats.accounts"
          :key="acc.user_id"
          :title="acc.nickname"
          :subtitle="statsPeriod === 'day' ? 'Perguntas por dia' : 'Perguntas por mês'"
          :series="acc.series"
          :period="statsPeriod"
          :color="ACCOUNT_CHART_COLORS[idx % ACCOUNT_CHART_COLORS.length]"
        />
      </div>
    </div>
  </div>
</template>

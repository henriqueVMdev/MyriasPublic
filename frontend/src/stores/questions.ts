import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { listQuestions, type Question, type QuestionAccount } from "@/api/questions";

const POLL_INTERVAL_MS = 30_000;
const SEEN_KEY = "myrias-questions-seen";
const SEEN_MAX = 500;
const DISMISSED_KEY = "myrias-questions-dismissed";
const DISMISSED_MAX = 500;

function loadSeen(): Set<number> {
  try {
    const raw = localStorage.getItem(SEEN_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr : []);
  } catch {
    return new Set();
  }
}

/** Trunca o Set in-place para os SEEN_MAX IDs mais recentes (insertion order). */
function trimSeen(seen: Set<number>) {
  if (seen.size <= SEEN_MAX) return;
  const arr = Array.from(seen);
  // Set itera em ordem de inserção — drop dos mais antigos.
  const drop = arr.length - SEEN_MAX;
  for (let i = 0; i < drop; i++) seen.delete(arr[i]);
}

function saveSeen(seen: Set<number>) {
  try {
    trimSeen(seen);
    localStorage.setItem(SEEN_KEY, JSON.stringify(Array.from(seen)));
  } catch {
    // ignore
  }
}

function loadDismissed(): Set<number> {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr : []);
  } catch {
    return new Set();
  }
}

function saveDismissed(dismissed: Set<number>) {
  try {
    if (dismissed.size > DISMISSED_MAX) {
      const arr = Array.from(dismissed);
      const drop = arr.length - DISMISSED_MAX;
      for (let i = 0; i < drop; i++) dismissed.delete(arr[i]);
    }
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(Array.from(dismissed)));
  } catch {
    // ignore
  }
}

export const useQuestionsStore = defineStore("questions", () => {
  const questions = ref<Question[]>([]);
  // Accounts e counts vêm na mesma resposta do listQuestions — guardamos
  // pra evitar que a página tenha que disparar um segundo HTTP.
  const accountsList = ref<QuestionAccount[]>([]);
  const accountCounts = ref<Record<string, number>>({});
  const loading = ref(false);
  const lastFetched = ref<Date | null>(null);
  const notificationsEnabled = ref(
    typeof Notification !== "undefined" && Notification.permission === "granted"
  );
  const seenIds = ref<Set<number>>(loadSeen());
  const dismissedIds = ref<Set<number>>(loadDismissed());

  let timer: ReturnType<typeof setInterval> | null = null;
  // Em vez de um boolean que descarta chamadas concorrentes, guardamos a
  // promise em vôo. Quem chamar `fetchNow` enquanto outra está rodando
  // espera ela terminar — evita perder o refetch pós-resposta.
  let inFlight: Promise<void> | null = null;

  // Tombstone: IDs respondidos recentemente. ML tem eventual consistency —
  // por alguns segundos depois do POST /answers, /questions/search ainda
  // pode devolver a pergunta como UNANSWERED. Sem isso, ela "reaparece"
  // na lista logo após a remoção otimista. Expira em 60s.
  const ANSWERED_TOMBSTONE_MS = 60_000;
  const recentlyAnswered = ref<Map<number, number>>(new Map()); // id → expiresAt

  function pruneTombstones() {
    const now = Date.now();
    let mutated = false;
    for (const [id, expiresAt] of recentlyAnswered.value) {
      if (expiresAt <= now) {
        recentlyAnswered.value.delete(id);
        mutated = true;
      }
    }
    if (mutated) {
      // Força reatividade do Map (Vue 3 ref<Map> precisa de reassign)
      recentlyAnswered.value = new Map(recentlyAnswered.value);
    }
  }

  // Conta filtrada já desconta tombstones — o badge da sidebar fica coerente.
  const unansweredCount = computed(() => questions.value.length);

  /** Remove uma pergunta do estado local e marca tombstone para evitar
   *  reaparição enquanto o ML não atualiza o status (eventual consistency). */
  function removeQuestion(id: number) {
    recentlyAnswered.value.set(id, Date.now() + ANSWERED_TOMBSTONE_MS);
    recentlyAnswered.value = new Map(recentlyAnswered.value);
    questions.value = questions.value.filter((q) => q.id !== id);
  }

  /** Oculta permanentemente uma pergunta (persiste no localStorage).
   *  Usar para perguntas travadas que o ML continua devolvendo como UNANSWERED
   *  mesmo depois de respondidas. */
  function dismissQuestion(id: number) {
    dismissedIds.value.add(id);
    dismissedIds.value = new Set(dismissedIds.value);
    saveDismissed(dismissedIds.value);
    questions.value = questions.value.filter((q) => q.id !== id);
  }

  /** Limpa todas as perguntas do estado — usar antes de um refetch manual
   *  para garantir que dados obsoletos não fiquem visíveis. */
  function clearQuestions() {
    questions.value = [];
    accountsList.value = [];
    accountCounts.value = {};
  }

  async function requestNotificationPermission(): Promise<boolean> {
    if (!("Notification" in window)) {
      notificationsEnabled.value = false;
      return false;
    }
    if (Notification.permission === "granted") {
      notificationsEnabled.value = true;
      return true;
    }
    if (Notification.permission === "denied") {
      notificationsEnabled.value = false;
      return false;
    }
    try {
      const result = await Notification.requestPermission();
      notificationsEnabled.value = result === "granted";
      return notificationsEnabled.value;
    } catch {
      return false;
    }
  }

  function fireNotification(q: Question) {
    if (!notificationsEnabled.value) return;
    // Vue Router está em history mode — a rota fica em pathname, não hash.
    // (antes checava `location.hash`, que nunca casava → notificação disparava
    // mesmo com a página aberta)
    if (
      document.hasFocus() &&
      window.location.pathname.includes("/atendimento/perguntas")
    ) {
      return;
    }
    try {
      const title = `Nova pergunta em ${q.account.nickname}`;
      const body = [
        q.buyer.nickname ? `${q.buyer.nickname}: ` : "",
        (q.text || "").slice(0, 140),
      ].join("");
      const n = new Notification(title, {
        body,
        icon: q.item.thumbnail || undefined,
        tag: `question-${q.id}`,
      });
      n.onclick = () => {
        window.focus();
        window.location.href = "/atendimento/perguntas";
        n.close();
      };
    } catch {
      // alguns browsers podem bloquear
    }
  }

  async function fetchNow(silentNotify = false) {
    // Se já tem um fetch em vôo, espera ele terminar e dispara um novo.
    // Isso garante que `submitAnswer → fetchNow()` nunca seja descartado.
    if (inFlight) {
      await inFlight;
    }
    const run = (async () => {
      loading.value = true;
      try {
        const resp = await listQuestions("UNANSWERED");
        // Filtra tombstones (respondidas recentemente) e dismissed (ocultadas manualmente).
        pruneTombstones();
        const fresh = resp.questions.filter(
          (q) => !recentlyAnswered.value.has(q.id) && !dismissedIds.value.has(q.id)
        );

        // Detecta novas perguntas (IDs que nunca vimos)
        const newOnes = fresh.filter((q) => !seenIds.value.has(q.id));
        if (!silentNotify && newOnes.length > 0 && lastFetched.value) {
          // Só notifica se não é a primeira carga da sessão
          for (const q of newOnes) fireNotification(q);
        }
        // Marca todas como vistas
        for (const q of fresh) seenIds.value.add(q.id);
        saveSeen(seenIds.value);

        questions.value = fresh;
        accountsList.value = resp.accounts || [];
        accountCounts.value = resp.counts || {};
        lastFetched.value = new Date();
      } catch (err) {
        console.error("Erro no polling de perguntas:", err);
      } finally {
        loading.value = false;
      }
    })();
    inFlight = run;
    try {
      await run;
    } finally {
      if (inFlight === run) inFlight = null;
    }
  }

  function startPolling() {
    if (timer) return;
    // fetch inicial marca como "silent" — não notifica sobre as já existentes
    fetchNow(true);
    timer = setInterval(() => {
      // Pausa quando aba em background pra economizar requests — volta no foco
      if (document.hidden) return;
      fetchNow();
    }, POLL_INTERVAL_MS);

    // Ao voltar pra aba, atualiza imediatamente
    document.addEventListener("visibilitychange", handleVisibility);
  }

  function handleVisibility() {
    if (!document.hidden) fetchNow();
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    document.removeEventListener("visibilitychange", handleVisibility);
  }

  return {
    questions,
    accountsList,
    accountCounts,
    loading,
    lastFetched,
    notificationsEnabled,
    unansweredCount,
    requestNotificationPermission,
    fetchNow,
    removeQuestion,
    dismissQuestion,
    clearQuestions,
    startPolling,
    stopPolling,
  };
});

import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { listUnread, type Conversation, type MessageAccount } from "@/api/messages";

const POLL_INTERVAL_MS = 30_000;
const SEEN_KEY = "myrias-messages-seen";

function loadSeen(): Set<string> {
  try {
    const raw = localStorage.getItem(SEEN_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr : []);
  } catch {
    return new Set();
  }
}

function saveSeen(seen: Set<string>) {
  try {
    const arr = Array.from(seen).slice(-500);
    localStorage.setItem(SEEN_KEY, JSON.stringify(arr));
  } catch {
    // ignore
  }
}

function convKey(c: Conversation): string {
  return `${c.account.user_id}:${c.pack_id}:${c.last_date || ""}`;
}

export const useMessagesStore = defineStore("messages", () => {
  const conversations = ref<Conversation[]>([]);
  const accounts = ref<MessageAccount[]>([]);
  const counts = ref<Record<string, number>>({});
  const loading = ref(false);
  const lastFetched = ref<Date | null>(null);
  const notificationsEnabled = ref(false);
  const seen = ref<Set<string>>(loadSeen());
  // Quando a tela de Mensagens está aberta, busca enriquecida (comprador + itens).
  // O polling do badge no resto do app fica leve (enrich=false).
  const enrich = ref(false);

  let timer: ReturnType<typeof setInterval> | null = null;
  let polling = false;

  const unreadTotal = computed(() =>
    conversations.value.reduce((acc, c) => acc + (c.unread_count || 0), 0)
  );

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

  function fireNotification(c: Conversation) {
    if (!notificationsEnabled.value) return;
    if (document.hasFocus() && window.location.pathname.includes("mensagens")) {
      return;
    }
    try {
      const title = `Nova mensagem em ${c.account.nickname}`;
      const body = (c.last_text || "").slice(0, 140);
      const n = new Notification(title, {
        body,
        tag: `message-${c.account.user_id}-${c.pack_id}`,
      });
      n.onclick = () => {
        window.focus();
        window.location.href = "/atendimento/mensagens";
        n.close();
      };
    } catch {
      // ignore
    }
  }

  async function fetchNow(silentNotify = false) {
    if (polling) return;
    polling = true;
    loading.value = true;
    try {
      const resp = await listUnread(enrich.value);
      const fresh = resp.conversations;

      const newOnes = fresh.filter((c) => !seen.value.has(convKey(c)));
      if (!silentNotify && newOnes.length > 0 && lastFetched.value) {
        for (const c of newOnes) fireNotification(c);
      }
      for (const c of fresh) seen.value.add(convKey(c));
      saveSeen(seen.value);

      conversations.value = fresh;
      accounts.value = resp.accounts;
      counts.value = resp.counts || {};
      lastFetched.value = new Date();
    } catch (err) {
      console.error("Erro no polling de mensagens:", err);
    } finally {
      loading.value = false;
      polling = false;
    }
  }

  function startPolling() {
    if (timer) return;
    fetchNow(true);
    timer = setInterval(() => {
      if (document.hidden) return;
      fetchNow();
    }, POLL_INTERVAL_MS);
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

  function removeConversation(accountUserId: number, packId: string) {
    conversations.value = conversations.value.filter(
      (c) => !(c.account.user_id === accountUserId && c.pack_id === packId)
    );
  }

  return {
    conversations,
    accounts,
    counts,
    loading,
    lastFetched,
    notificationsEnabled,
    enrich,
    unreadTotal,
    requestNotificationPermission,
    fetchNow,
    startPolling,
    stopPolling,
    removeConversation,
  };
});

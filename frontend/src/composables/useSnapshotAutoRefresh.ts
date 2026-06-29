import { ref, onBeforeUnmount } from "vue";
import { refreshSnapshot, getSnapshotStatus } from "@/api/performance";
import { useAuthStore } from "@/stores/auth";

/**
 * Auto-refresh em segundo plano dos snapshots de performance (inventário + vendas),
 * compartilhado pelas telas que leem o mesmo cache (Performance e Anúncios Repetidos).
 *
 * Ao chamar `trigger()`, dispara a varredura em background no backend (que não
 * empilha — ver `_refresh_in_progress` em app/api/performance.py) e faz polling
 * de `/snapshot-status` até terminar; aí chama `reload()` pra revelar os dados.
 */
export function useSnapshotAutoRefresh(opts: {
  /** Escopo de conta: "all" ou um user_id em string. */
  account: () => string;
  /** Recarrega os dados da página quando a varredura termina. */
  reload: () => Promise<void> | void;
}) {
  const auth = useAuthStore();
  const autoRefreshing = ref(false);
  let statusTimer: ReturnType<typeof setInterval> | null = null;

  function scopeUserIds(): number[] {
    const acc = opts.account();
    if (acc && acc !== "all") return [Number(acc)];
    return auth.accounts.map((a) => a.user_id);
  }

  function stopPoll() {
    if (statusTimer) {
      clearInterval(statusTimer);
      statusTimer = null;
    }
  }

  function startPoll() {
    if (statusTimer) return;
    statusTimer = setInterval(checkStatus, 4000);
  }

  async function checkStatus() {
    if (document.hidden) return; // pausa em aba de fundo
    let stillRunning = false;
    try {
      const { accounts } = await getSnapshotStatus();
      const scope = new Set(scopeUserIds());
      stillRunning = accounts.some((a) => scope.has(a.user_id) && a.refreshing);
    } catch (err) {
      console.error("Erro no polling de status:", err);
      return;
    }
    if (!stillRunning) {
      stopPoll();
      autoRefreshing.value = false;
      await opts.reload();
    }
  }

  /**
   * Dispara a varredura em segundo plano e acompanha até terminar.
   * mode "auto" (padrão) = vendas+ads rápido, inventário só se velho.
   * mode "full" = força a varredura completa (botão "Atualizar dados").
   */
  async function trigger(mode: "auto" | "full" = "auto") {
    try {
      await refreshSnapshot(opts.account() || "all", true, mode);
    } catch (err) {
      console.error("Erro ao disparar refresh em background:", err);
      return;
    }
    autoRefreshing.value = true;
    startPoll();
  }

  onBeforeUnmount(stopPoll);

  return { autoRefreshing, trigger };
}

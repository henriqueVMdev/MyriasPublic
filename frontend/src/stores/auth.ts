import { defineStore } from "pinia";
import { computed, ref } from "vue";
import {
  getAuthStatus,
  getAccounts,
  switchAccount as apiSwitchAccount,
  removeAccount as apiRemoveAccount,
  logout as apiLogout,
  type AuthStatus,
  type MeliAccount,
} from "@/api/auth";
import { getSession, appLogout, type SessionInfo } from "@/api/users";

type SessionUser = NonNullable<SessionInfo["user"]>;

export const useAuthStore = defineStore("auth", () => {
  const status = ref<AuthStatus>({ authenticated: false });
  const accounts = ref<MeliAccount[]>([]);
  const loading = ref(false);

  // ---- Sessão do painel (usuário humano logado) ----
  const me = ref<SessionUser | null>(null);
  const appAuthenticated = ref(false);
  // Bootstrap: nenhum usuário cadastrado ainda → painel liberado (acesso total)
  // até o admin inicial ser criado pelo script.
  const bootstrapMode = ref(false);
  const sessionLoaded = ref(false);

  async function loadSession(): Promise<boolean> {
    try {
      const data = await getSession();
      appAuthenticated.value = !!data.authenticated;
      me.value = data.user ?? null;
      bootstrapMode.value = data.password_required === false;
    } catch {
      appAuthenticated.value = false;
      me.value = null;
      bootstrapMode.value = false;
    } finally {
      sessionLoaded.value = true;
    }
    return appAuthenticated.value;
  }

  /** O usuário logado tem a permissão (seção ou ação)? Admin e bootstrap
   *  têm acesso total. */
  function can(key: string): boolean {
    if (bootstrapMode.value) return true;
    const u = me.value;
    if (!u) return false;
    if (u.is_admin) return true;
    return (u.permissions || []).includes(key);
  }

  const isAdmin = computed(() => bootstrapMode.value || !!me.value?.is_admin);

  async function appLogoutAndReset() {
    try {
      await appLogout();
    } finally {
      me.value = null;
      appAuthenticated.value = false;
      sessionLoaded.value = false;
    }
  }

  async function checkAuth() {
    loading.value = true;
    try {
      const [s, a] = await Promise.all([getAuthStatus(), getAccounts()]);
      status.value = s;
      accounts.value = a;
    } catch {
      status.value = { authenticated: false };
      accounts.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function switchAccount(userId: number) {
    try {
      status.value = await apiSwitchAccount(userId);
      // Atualizar lista de contas
      accounts.value = accounts.value.map((a) => ({
        ...a,
        is_active: a.user_id === userId,
      }));
    } catch (e) {
      console.error("Erro ao trocar conta:", e);
    }
  }

  async function removeAccount(userId: number) {
    try {
      await apiRemoveAccount(userId);
      await checkAuth();
    } catch (e) {
      console.error("Erro ao remover conta:", e);
    }
  }

  async function logout() {
    await apiLogout();
    await checkAuth();
  }

  return {
    status,
    accounts,
    loading,
    checkAuth,
    switchAccount,
    removeAccount,
    logout,
    // Sessão do painel / permissões
    me,
    appAuthenticated,
    bootstrapMode,
    sessionLoaded,
    isAdmin,
    loadSession,
    can,
    appLogoutAndReset,
  };
});

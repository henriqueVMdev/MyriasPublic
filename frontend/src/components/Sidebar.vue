<script setup lang="ts">
import { ref, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useQuestionsStore } from "@/stores/questions";
import { useMessagesStore } from "@/stores/messages";
import { getLoginUrl } from "@/api/auth";
import ConfirmPasswordModal from "./ConfirmPasswordModal.vue";
import {
  LayoutDashboard,
  BarChart3,
  BrainCircuit,
  Layers,
  CopyMinus,
  Copy,
  Tag,
  ListChecks,
  ScrollText,
  FileSpreadsheet,
  LogOut,
  LogIn,
  ChevronDown,
  ChevronUp,
  UserPlus,
  UserCircle,
  Check,
  X,
  Users,
  MessageCircle,
  MessageSquare,
  ChevronLeft,
  ChevronRight,
} from "lucide-vue-next";

const props = defineProps<{ collapsed?: boolean }>();
const emit = defineEmits<{ navigate: []; "toggle-collapse": [] }>();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const questionsStore = useQuestionsStore();
const messagesStore = useMessagesStore();

const showAccountMenu = ref(false);
// Área de conta ML recolhida por padrão — deixa a sidebar mais limpa.
const accountOpen = ref(false);

// Classe utilitária: esconde no desktop quando a sidebar está recolhida.
// No mobile (overlay) a sidebar abre sempre expandida.
const hideCls = computed(() => (props.collapsed ? "lg:hidden" : ""));

const navSections = [
  {
    label: "",
    items: [
      { to: "/", label: "Dashboard", icon: LayoutDashboard, perm: "dashboard" },
      { to: "/items", label: "Performance", icon: BarChart3, perm: "performance" },
      { to: "/repetidos", label: "Anúncios Repetidos", icon: CopyMinus, perm: "repetidos" },
      { to: "/qualidade", label: "Anúncios Incompletos", icon: ListChecks, perm: "quality" },
      { to: "/bulk", label: "Edição por SKU", icon: Layers, perm: "bulk" },
      { to: "/clone", label: "Copiar Anúncio", icon: Copy, perm: "clone" },
      { to: "/promocoes", label: "Promoções", icon: Tag, perm: "promocoes" },
      { to: "/logs", label: "Histórico", icon: ScrollText, anyPerm: ["logs", "atendimento_historico"] },
      { to: "/planilhas", label: "Planilhas", icon: FileSpreadsheet, perm: "planilhas" },
    ],
  },
  {
    label: "Atendimento",
    items: [
      { to: "/atendimento/perguntas", label: "Perguntas", icon: MessageCircle, perm: "perguntas" },
      { to: "/atendimento/mensagens", label: "Mensagens", icon: MessageSquare, perm: "mensagens" },
    ],
  },
  {
    label: "Administração",
    items: [
      { to: "/usuarios", label: "Usuários", icon: Users, adminOnly: true },
      { to: "/ia", label: "Agentes de IA", icon: BrainCircuit, adminOnly: true },
    ],
  },
];

// Filtra itens/seções pelas permissões do usuário logado (admin vê tudo).
const visibleSections = computed(() =>
  navSections
    .map((section) => ({
      ...section,
      items: section.items.filter((item: any) => {
        if (item.adminOnly) return auth.isAdmin;
        if (item.anyPerm) return item.anyPerm.some((p: string) => auth.can(p));
        return auth.can(item.perm);
      }),
    }))
    .filter((section) => section.items.length > 0)
);

async function logoutPanel() {
  await auth.appLogoutAndReset();
  router.replace({ name: "login" });
}

const isActive = (path: string) =>
  path === "/" ? route.path === "/" : route.path.startsWith(path);

function badgeCount(to: string): number {
  if (to === "/atendimento/perguntas") return questionsStore.unansweredCount;
  if (to === "/atendimento/mensagens") return messagesStore.unreadTotal;
  return 0;
}

const authenticated = computed(() => auth.status.authenticated);
const activeAccount = computed(() =>
  auth.accounts.find((a) => a.is_active) || null
);

async function selectAccount(userId: number) {
  await auth.switchAccount(userId);
  showAccountMenu.value = false;
  router.push("/");
  emit("navigate");
}

// Confirmação pesada (exige senha do app) pra desconexão de conta ML.
type PendingDisconnect = { kind: "remove"; userId: number; nickname: string };

const confirmOpen = ref(false);
const pending = ref<PendingDisconnect | null>(null);

const confirmMessage = computed(() => {
  if (!pending.value) return "";
  const nick = pending.value.nickname;
  return `Você vai desconectar a conta "${nick}". Digite a senha do app para confirmar.`;
});

function askRemoveAccount(userId: number, nickname: string) {
  pending.value = { kind: "remove", userId, nickname };
  confirmOpen.value = true;
}

async function onConfirmDisconnect() {
  const p = pending.value;
  confirmOpen.value = false;
  pending.value = null;
  if (!p) return;
  // Desconectar a última conta = sair do ML por completo.
  if (auth.accounts.length <= 1) {
    await auth.logout();
  } else {
    await auth.removeAccount(p.userId);
  }
  showAccountMenu.value = false;
}

function onCancelDisconnect() {
  confirmOpen.value = false;
  pending.value = null;
}
</script>

<template>
  <aside
    class="relative w-60 flex flex-col h-full border-r transition-all duration-200
           bg-brand-yellow border-brand-yellow-dark
           dark:bg-brand-black-soft dark:border-zinc-800"
    :class="collapsed ? 'lg:w-[72px]' : 'lg:w-60'"
  >
    <!-- Handle discreto na borda direita (só desktop): retrai / expande a sidebar -->
    <button
      @click="emit('toggle-collapse')"
      class="hidden lg:flex absolute top-1/2 -translate-y-1/2 -right-3 z-50 w-6 h-6 items-center justify-center
             rounded-full border shadow-sm transition-colors
             bg-white text-gray-500 border-gray-200 hover:text-gray-800 hover:bg-gray-50
             dark:bg-zinc-800 dark:text-gray-400 dark:border-zinc-700 dark:hover:text-gray-100"
      :title="collapsed ? 'Expandir menu' : 'Recolher menu'"
      aria-label="Recolher ou expandir menu"
    >
      <ChevronRight v-if="collapsed" :size="15" />
      <ChevronLeft v-else :size="15" />
    </button>
    <!-- Logo -->
    <div
      class="p-4 border-b border-black/10 dark:border-zinc-800 flex items-center gap-3"
      :class="collapsed ? 'lg:justify-center lg:px-2' : ''"
    >
      <img src="/logo_favicon.svg" alt="HRB Imports" class="w-10 h-10 flex-shrink-0 dark:hidden" />
      <img src="/logo dourado 1.svg" alt="HRB Imports" class="w-10 h-10 flex-shrink-0 hidden dark:block" />
      <div class="min-w-0" :class="hideCls">
        <h1 class="text-base font-extrabold tracking-tight text-brand-black dark:text-brand-yellow truncate">
          HRB Imports
        </h1>
        <p class="text-[10px] font-semibold uppercase tracking-[0.22em] text-black/50 dark:text-gray-400">
          Painel interno
        </p>
      </div>
    </div>

    <!-- Navigation -->
    <nav class="flex-1 p-3 space-y-3 overflow-y-auto overflow-x-hidden">
      <div
        v-for="(section, idx) in visibleSections"
        :key="idx"
        class="space-y-1"
      >
        <div
          v-if="section.label"
          class="px-3 pt-2 pb-1 text-[10px] font-bold uppercase tracking-[0.18em] text-black/45 dark:text-gray-500"
          :class="hideCls"
        >
          {{ section.label }}
        </div>
        <div
          v-if="section.label && collapsed"
          class="hidden lg:block mx-2 my-2 border-t border-black/15 dark:border-zinc-800"
          aria-hidden="true"
        ></div>
        <router-link
          v-for="item in section.items"
          :key="item.to"
          :to="item.to"
          class="relative flex items-center gap-3 px-3 py-2 rounded-xl text-sm font-semibold transition-all duration-150"
          :class="[
            isActive(item.to)
              ? 'bg-brand-black text-brand-yellow shadow-md dark:bg-brand-yellow dark:text-brand-black'
              : 'text-gray-800 hover:bg-white/40 hover:translate-x-0.5 dark:text-gray-300 dark:hover:bg-zinc-800 dark:hover:text-gray-100',
            collapsed ? 'lg:justify-center lg:px-2' : '',
          ]"
          :title="collapsed ? item.label : undefined"
          @click="emit('navigate')"
        >
          <component :is="item.icon" :size="18" class="flex-shrink-0" />
          <span class="flex-1 truncate" :class="hideCls">{{ item.label }}</span>
          <span
            v-if="badgeCount(item.to) > 0"
            class="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-red-500 text-white"
            :class="hideCls"
          >
            {{ badgeCount(item.to) }}
          </span>
          <!-- Indicador compacto quando recolhida -->
          <span
            v-if="badgeCount(item.to) > 0 && collapsed"
            class="hidden lg:block absolute top-1 right-1 w-2 h-2 rounded-full bg-red-500 ring-2 ring-brand-yellow dark:ring-brand-black-soft"
          ></span>
        </router-link>
      </div>
    </nav>

    <!-- Account section -->
    <div class="p-3">
      <!-- ===== Modo expandido ===== -->
      <div :class="hideCls">
        <!-- Linha discreta que retrai a área de conta ML (fechada por padrão) -->
        <button
          @click="accountOpen = !accountOpen"
          class="w-full flex items-center gap-2 px-1 py-1.5 mb-1 transition-colors
                 text-[10px] font-semibold uppercase tracking-wider
                 text-black/40 hover:text-black/70 dark:text-gray-500 dark:hover:text-gray-300"
        >
          <span class="h-px flex-1 bg-black/10 dark:bg-zinc-800"></span>
          <span class="inline-flex items-center gap-1">
            <span v-if="authenticated" class="w-1.5 h-1.5 rounded-full bg-green-500"></span>
            Conta ML
            <component :is="accountOpen ? ChevronUp : ChevronDown" :size="12" />
          </span>
          <span class="h-px flex-1 bg-black/10 dark:bg-zinc-800"></span>
        </button>

        <div v-show="accountOpen">
        <template v-if="authenticated && auth.accounts.length > 0">
          <!-- Account selector button -->
          <div class="relative">
            <button
              @click="showAccountMenu = !showAccountMenu"
              class="flex items-center gap-2 w-full px-3 py-2 rounded-xl text-sm font-semibold transition-colors
                     text-gray-900 bg-white/50 border border-black/10 hover:bg-white/80
                     dark:text-gray-200 dark:bg-zinc-900 dark:border-zinc-700 dark:hover:bg-zinc-800"
            >
              <span class="relative flex-shrink-0">
                <Users :size="16" />
                <span class="absolute -top-0.5 -right-0.5 w-1.5 h-1.5 rounded-full bg-green-500"></span>
              </span>
              <span class="truncate flex-1 text-left">
                {{ activeAccount?.nickname || "Selecionar conta" }}
              </span>
              <ChevronDown
                :size="14"
                class="flex-shrink-0 transition-transform"
                :class="{ 'rotate-180': showAccountMenu }"
              />
            </button>

            <!-- Dropdown -->
            <div
              v-if="showAccountMenu"
              class="absolute bottom-full left-0 right-0 mb-1 rounded-xl shadow-lg overflow-hidden z-50
                     bg-white border border-gray-200
                     dark:bg-brand-black-soft dark:border-zinc-700"
            >
              <div class="max-h-48 overflow-y-auto">
                <div
                  v-for="account in auth.accounts"
                  :key="account.user_id"
                  role="button"
                  tabindex="0"
                  @click="selectAccount(account.user_id)"
                  @keydown.enter="selectAccount(account.user_id)"
                  @keydown.space.prevent="selectAccount(account.user_id)"
                  class="flex items-center gap-2 w-full px-3 py-2 text-sm transition-colors cursor-pointer
                         hover:bg-brand-yellow-soft dark:hover:bg-zinc-800
                         dark:text-gray-200"
                  :class="account.is_active ? 'bg-brand-yellow-soft font-medium dark:bg-zinc-800' : ''"
                >
                  <Check
                    v-if="account.is_active"
                    :size="14"
                    class="text-brand-black dark:text-brand-yellow flex-shrink-0"
                  />
                  <div v-else class="w-3.5 flex-shrink-0" />
                  <span class="truncate flex-1 text-left">{{ account.nickname }}</span>
                  <button
                    type="button"
                    @click.stop="askRemoveAccount(account.user_id, account.nickname)"
                    class="p-0.5 rounded hover:bg-red-100 dark:hover:bg-red-900/40 flex-shrink-0"
                    title="Desconectar"
                  >
                    <X :size="12" class="text-red-500" />
                  </button>
                </div>
              </div>

              <!-- Add account -->
              <a
                :href="getLoginUrl()"
                class="flex items-center gap-2 w-full px-3 py-2 text-sm border-t transition-colors
                       text-brand-black hover:bg-brand-yellow-soft border-gray-200
                       dark:text-brand-yellow dark:hover:bg-zinc-800 dark:border-zinc-700"
              >
                <UserPlus :size="14" />
                Conectar outra conta
              </a>
            </div>
          </div>

        </template>

        <template v-else>
          <a
            :href="getLoginUrl()"
            class="flex items-center gap-3 w-full px-3 py-2 rounded-xl text-sm font-medium transition-colors
                   text-gray-800 hover:bg-white/40
                   dark:text-gray-200 dark:hover:bg-zinc-800"
          >
            <LogIn :size="18" />
            Conectar ao ML
          </a>
        </template>
        </div>

        <!-- Usuário logado + sair do painel -->
        <div v-if="auth.me" class="mt-2 pt-2 border-t border-black/10 dark:border-zinc-800">
          <div class="px-3 py-1 flex items-center gap-2 text-xs text-gray-700 dark:text-gray-400">
            <UserCircle :size="16" class="flex-shrink-0" />
            <span class="truncate">{{ auth.me.display_name || auth.me.username }}</span>
            <span
              v-if="auth.me.is_admin"
              class="ml-auto text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded
                     bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black"
            >admin</span>
          </div>
          <button
            @click="logoutPanel()"
            class="flex items-center gap-3 w-full px-3 py-2 mt-1 rounded-xl text-sm font-medium transition-colors
                   text-gray-800 hover:bg-white/40
                   dark:text-gray-200 dark:hover:bg-zinc-800"
          >
            <LogOut :size="18" />
            Sair
          </button>
        </div>
      </div>

      <!-- ===== Modo recolhido (só desktop) — ícones compactos ===== -->
      <div v-if="collapsed" class="hidden lg:flex flex-col items-center gap-1">
        <button
          @click="emit('toggle-collapse')"
          class="p-2.5 rounded-xl transition-colors text-gray-800 hover:bg-white/40
                 dark:text-gray-300 dark:hover:bg-zinc-800"
          :title="activeAccount?.nickname || 'Contas'"
        >
          <span class="relative block">
            <Users :size="18" />
            <span
              v-if="authenticated"
              class="absolute -top-0.5 -right-0.5 w-1.5 h-1.5 rounded-full bg-green-500"
            ></span>
          </span>
        </button>
      </div>

    </div>

    <!-- Confirmação pesada de desconexão (exige senha do app) -->
    <ConfirmPasswordModal
      :open="confirmOpen"
      title="Desconectar conta"
      :message="confirmMessage"
      confirm-label="Desconectar"
      @confirmed="onConfirmDisconnect"
      @cancel="onCancelDisconnect"
    />
  </aside>
</template>

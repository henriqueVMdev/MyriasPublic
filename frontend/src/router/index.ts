import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/pages/AppLogin.vue"),
    },
    {
      path: "/sobre",
      name: "sobre",
      component: () => import("@/pages/AboutPage.vue"),
    },
    {
      path: "/",
      component: () => import("@/components/Layout.vue"),
      children: [
        {
          path: "",
          name: "dashboard",
          component: () => import("@/pages/Dashboard.vue"),
          meta: { perm: "dashboard" },
        },
        {
          path: "items",
          name: "items",
          component: () => import("@/pages/Performance.vue"),
          meta: { perm: "performance" },
        },
        {
          // Declarar antes de items/:id pra não casar 'sku' como id
          path: "items/sku/:sku",
          name: "sku-performance",
          component: () => import("@/pages/SkuPerformance.vue"),
          meta: { perm: "performance" },
        },
        {
          path: "items/:id",
          name: "item-performance",
          component: () => import("@/pages/ItemPerformance.vue"),
          meta: { perm: "performance" },
        },
        {
          path: "repetidos",
          name: "duplicates",
          component: () => import("@/pages/Duplicates.vue"),
          meta: { perm: "repetidos" },
        },
        {
          // Mesma página do SKU, mas em modo de exclusão (allowDelete)
          path: "repetidos/sku/:sku",
          name: "sku-duplicates",
          component: () => import("@/pages/SkuPerformance.vue"),
          meta: { allowDelete: true, perm: "repetidos" },
        },
        {
          path: "bulk",
          name: "bulk-by-sku",
          component: () => import("@/pages/BulkBySku.vue"),
          meta: { perm: "bulk" },
        },
        {
          path: "bulk/sku/:sku",
          name: "bulk-edit",
          component: () => import("@/pages/BulkEdit.vue"),
          meta: { perm: "bulk" },
        },
        {
          path: "clone",
          name: "clone",
          component: () => import("@/pages/CloneListing.vue"),
          meta: { perm: "clone" },
        },
        {
          path: "promocoes",
          name: "promotions",
          component: () => import("@/pages/Promotions.vue"),
          meta: { perm: "promocoes" },
        },
        {
          path: "promocoes/:id",
          name: "promotion-detail",
          component: () => import("@/pages/PromotionDetail.vue"),
          meta: { perm: "promocoes" },
        },
        {
          path: "logs",
          name: "logs",
          component: () => import("@/pages/History.vue"),
          // Visível pra quem tem Histórico de Operações OU de Atendimento.
          meta: { anyPerm: ["logs", "atendimento_historico"] },
        },
        {
          path: "logs/:key",
          name: "operation-detail",
          component: () => import("@/pages/OperationDetail.vue"),
          meta: { perm: "logs" },
        },
        {
          path: "planilhas",
          name: "script-logs",
          component: () => import("@/pages/ScriptLogs.vue"),
          meta: { perm: "planilhas" },
        },
        {
          path: "atendimento/perguntas",
          name: "questions",
          component: () => import("@/pages/Questions.vue"),
          meta: { perm: "perguntas" },
        },
        {
          path: "atendimento/mensagens",
          name: "messages",
          component: () => import("@/pages/Messages.vue"),
          meta: { perm: "mensagens" },
        },
        {
          // Link antigo passou a viver dentro de /logs (aba Atendimento).
          path: "atendimento/historico",
          redirect: { name: "logs", query: { tab: "atendimento" } },
        },
        {
          path: "qualidade",
          name: "quality",
          component: () => import("@/pages/ListingQuality.vue"),
          meta: { perm: "quality" },
        },
        {
          path: "usuarios",
          name: "users",
          component: () => import("@/pages/Users.vue"),
          meta: { admin: true },
        },
        {
          path: "ia",
          name: "ai-agents",
          component: () => import("@/pages/AiAgents.vue"),
          meta: { admin: true },
        },
      ],
    },
  ],
});

// Ordem das seções (espelha a Sidebar) — usada para escolher pra onde mandar
// um usuário sem permissão na rota pedida.
const SECTION_ROUTES: { perm: string; path: string }[] = [
  { perm: "dashboard", path: "/" },
  { perm: "performance", path: "/items" },
  { perm: "repetidos", path: "/repetidos" },
  { perm: "bulk", path: "/bulk" },
  { perm: "clone", path: "/clone" },
  { perm: "promocoes", path: "/promocoes" },
  { perm: "logs", path: "/logs" },
  { perm: "planilhas", path: "/planilhas" },
  { perm: "perguntas", path: "/atendimento/perguntas" },
  { perm: "mensagens", path: "/atendimento/mensagens" },
  { perm: "atendimento_historico", path: "/atendimento/historico" },
  { perm: "quality", path: "/qualidade" },
];

function firstAllowedPath(auth: ReturnType<typeof useAuthStore>): string | null {
  if (auth.isAdmin) return "/";
  const match = SECTION_ROUTES.find((s) => auth.can(s.perm));
  return match ? match.path : null;
}

router.beforeEach(async (to) => {
  // Rotas públicas (login e página institucional)
  if (to.name === "login" || to.name === "sobre") return true;

  const auth = useAuthStore();
  if (!auth.sessionLoaded) await auth.loadSession();

  if (!auth.appAuthenticated) return { name: "login" };

  // Rota só de admin
  if (to.meta?.admin && !auth.isAdmin) {
    return firstAllowedPath(auth) || { name: "sobre" };
  }

  // Permissão de seção (chave única)
  const perm = to.meta?.perm as string | undefined;
  if (perm && !auth.can(perm)) {
    return firstAllowedPath(auth) || { name: "sobre" };
  }

  // Permissão de seção (qualquer uma de uma lista — ex.: Histórico unificado)
  const anyPerm = to.meta?.anyPerm as string[] | undefined;
  if (anyPerm && !anyPerm.some((p) => auth.can(p))) {
    return firstAllowedPath(auth) || { name: "sobre" };
  }

  return true;
});

export default router;

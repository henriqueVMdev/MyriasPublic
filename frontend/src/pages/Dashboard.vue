<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import {
  getDashboardSummary,
  getDashboardRevenue,
  getDashboardReputation,
  getDashboardSales,
  getDashboardPerformance,
  type DashboardSummary,
  type DashboardRevenue,
  type ReputationResponse,
  type SalesResponse,
  type PerformanceData,
} from "@/api/dashboard";
import RevenueChart from "@/components/RevenueChart.vue";
import PerformanceChart from "@/components/PerformanceChart.vue";
import ReputationCards from "@/components/ReputationCards.vue";
import SalesByDayChart from "@/components/SalesByDayChart.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";
import {
  Package,
  PauseCircle,
  XCircle,
  Layers,
  AlertTriangle,
  TrendingUp,
  ShoppingCart,
  Award,
  Loader2,
} from "lucide-vue-next";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const authError = ref<string | null>(null);
const summary = ref<DashboardSummary | null>(null);
// ▼▼▼ BLOCO FATURAMENTO/DESEMPENHO — desativado no template via v-if="false".
// Refs/funções mantidas aqui pra que o template ainda compile. Quando o
// sistema de permissões/hierarquia ficar pronto, trocar v-if="false" pela
// flag de permissão (ex.: v-if="podeVerFinanceiro").
const revenue = ref<DashboardRevenue | null>(null);
const reputation = ref<ReputationResponse | null>(null);
const loading = ref(false);
const revenueLoading = ref(false);
const reputationLoading = ref(false);
const revenueDays = ref(30);
const chartMode = ref<"revenue" | "performance">("revenue");
const performance = ref<PerformanceData | null>(null);
const performanceLoading = ref(false);
// ▲▲▲

// Vendas por intervalo
const sales = ref<SalesResponse | null>(null);
const salesLoading = ref(false);
const salesDays = ref<number>(1);
const SALES_RANGES: Array<{ days: number; label: string }> = [
  { days: 1, label: "Hoje" },
  { days: 7, label: "7d" },
  { days: 14, label: "14d" },
  { days: 30, label: "30d" },
];

// ── Seleção de contas exibidas no dashboard ──────────────────────────────
// Cada conta vira um card clicável no topo (bolinha verde = ativa/mostrando).
// As infos (contagens, vendas, reputação) refletem só as contas selecionadas.
const SELECTED_KEY = "myrias-dashboard-accounts";
const selectedAccounts = ref<Set<number>>(new Set());

function loadSelected(allIds: number[]): Set<number> {
  try {
    const raw = localStorage.getItem(SELECTED_KEY);
    if (raw) {
      const arr = (JSON.parse(raw) as number[]).filter((id) => allIds.includes(id));
      if (arr.length) return new Set(arr);
    }
  } catch {
    // ignore
  }
  return new Set(allIds); // padrão: todas as contas
}

function persistSelected() {
  try {
    localStorage.setItem(SELECTED_KEY, JSON.stringify([...selectedAccounts.value]));
  } catch {
    // ignore
  }
}

function initSelection() {
  const ids = auth.accounts.map((a) => a.user_id);
  if (ids.length) selectedAccounts.value = loadSelected(ids);
}

const isSelected = (id: number) => selectedAccounts.value.has(id);

function toggleAccount(id: number) {
  const s = new Set(selectedAccounts.value);
  if (s.has(id)) {
    if (s.size <= 1) return; // mantém ao menos uma conta visível
    s.delete(id);
  } else {
    s.add(id);
  }
  selectedAccounts.value = s;
  persistSelected();
}

// Param pro backend: "all" quando todas estão marcadas; senão os ids.
const accountsParam = computed(() => {
  const ids = [...selectedAccounts.value];
  if (!ids.length || ids.length === auth.accounts.length) return "all";
  return ids.join(",");
});

// Vendas filtradas pelas contas selecionadas (totais recalculados).
const salesView = computed<SalesResponse | null>(() => {
  const s = sales.value;
  if (!s) return null;
  const items = s.items.filter((it) => selectedAccounts.value.has(it.account.user_id));
  return {
    ...s,
    items,
    total_quantity: items.reduce((a, it) => a + it.quantity, 0),
    total_revenue: Math.round(items.reduce((a, it) => a + it.revenue, 0) * 100) / 100,
    accounts: s.accounts.filter((a) => selectedAccounts.value.has(a.user_id)),
  };
});

// Reputação só das contas selecionadas.
const filteredReputation = computed(() =>
  (reputation.value?.accounts ?? []).filter((a) => selectedAccounts.value.has(a.user_id))
);

async function loadSummary() {
  if (!auth.status.authenticated) return;
  loading.value = true;
  try {
    summary.value = await getDashboardSummary(accountsParam.value);
  } catch {
    summary.value = null;
  } finally {
    loading.value = false;
  }
}

async function loadData() {
  if (!auth.status.authenticated) return;
  await loadSummary();
  // Faturamento só pra quem tem permissão (o bloco fica escondido pros demais).
  if (auth.can("dashboard_revenue")) loadRevenue();
  // Reputação e vendas vêm de todas as contas e são filtradas no client.
  loadReputation();
  loadSales();
}

// Recarrega só as contagens quando muda a seleção (vendas/reputação são
// filtradas no client, sem refetch).
watch(selectedAccounts, () => {
  if (auth.status.authenticated) loadSummary();
});

async function loadSales() {
  salesLoading.value = true;
  try {
    sales.value = await getDashboardSales(salesDays.value);
  } catch {
    sales.value = null;
  } finally {
    salesLoading.value = false;
  }
}

function changeSalesRange(d: number) {
  salesDays.value = d;
  loadSales();
}

async function loadReputation() {
  reputationLoading.value = true;
  try {
    reputation.value = await getDashboardReputation();
  } catch {
    reputation.value = null;
  } finally {
    reputationLoading.value = false;
  }
}

// Datas customizadas (modo "personalizado")
const revenueFrom = ref<string>("");
const revenueTo = ref<string>("");
const revenueRangeError = ref<string>("");

async function loadRevenue() {
  revenueLoading.value = true;
  revenueRangeError.value = "";
  try {
    if (revenueFrom.value && revenueTo.value) {
      const from = new Date(revenueFrom.value);
      const to = new Date(revenueTo.value);
      const diff = Math.floor((to.getTime() - from.getTime()) / 86_400_000) + 1;
      if (diff < 1) {
        revenueRangeError.value = "Data inicial precisa ser anterior ou igual à final.";
        revenue.value = null;
        return;
      }
      if (diff > 90) {
        revenueRangeError.value = "Intervalo máximo de 90 dias.";
        revenue.value = null;
        return;
      }
      revenue.value = await getDashboardRevenue({
        from: revenueFrom.value,
        to: revenueTo.value,
      });
    } else {
      revenue.value = await getDashboardRevenue({ days: revenueDays.value });
    }
  } catch {
    revenue.value = null;
  } finally {
    revenueLoading.value = false;
  }
}

async function loadPerformance() {
  performanceLoading.value = true;
  try {
    if (revenueFrom.value && revenueTo.value) {
      performance.value = await getDashboardPerformance({ from: revenueFrom.value, to: revenueTo.value });
    } else {
      performance.value = await getDashboardPerformance({ days: revenueDays.value });
    }
  } catch {
    performance.value = null;
  } finally {
    performanceLoading.value = false;
  }
}

// Carrega desempenho quando o usuário troca para essa aba (lazy)
watch(chartMode, (mode) => {
  if (mode === "performance" && performance.value === null && !performanceLoading.value) {
    loadPerformance();
  }
});

function changeRevenuePeriod(days: number) {
  revenueDays.value = days;
  revenueFrom.value = "";
  revenueTo.value = "";
  performance.value = null;
  loadRevenue();
  if (chartMode.value === "performance") loadPerformance();
}

function applyCustomRange() {
  if (!revenueFrom.value || !revenueTo.value) {
    revenueRangeError.value = "Preencha as duas datas.";
    return;
  }
  performance.value = null;
  loadRevenue();
  if (chartMode.value === "performance") loadPerformance();
}

const todayISO = computed(() => new Date().toISOString().slice(0, 10));

onMounted(async () => {
  // Detectar erro de callback OAuth (ex: conta não conectou)
  const err = route.query.auth_error as string | undefined;
  if (err) {
    authError.value = err;
    router.replace({ query: {} });
  }

  if (auth.loading) {
    const unwatch = auth.$subscribe(() => {
      if (!auth.loading) {
        initSelection();
        loadData();
        unwatch();
      }
    });
  } else {
    initSelection();
    await loadData();
  }
});

// Recarrega os dados quando o usuário troca de conta
watch(
  () => auth.status.user_id,
  (newId, oldId) => {
    if (newId !== oldId && auth.status.authenticated && !auth.loading) {
      loadData();
    }
  }
);

// Revalida a seleção quando contas são conectadas/removidas.
watch(
  () => auth.accounts.map((a) => a.user_id).join(","),
  () => initSelection()
);

const itemCards = [
  { key: "active", label: "Ativos", icon: Package, color: "text-green-600 bg-green-50", accent: "bg-green-500", status: "active" },
  { key: "paused", label: "Pausados", icon: PauseCircle, color: "text-yellow-600 bg-yellow-50", accent: "bg-yellow-400", status: "paused" },
  { key: "pending", label: "Pendentes", icon: AlertTriangle, color: "text-orange-600 bg-orange-50", accent: "bg-orange-500", status: "pending" },
  { key: "closed", label: "Encerrados", icon: XCircle, color: "text-red-600 bg-red-50", accent: "bg-red-500", status: "closed" },
  { key: "total", label: "Total", icon: Layers, color: "text-gray-900 bg-brand-yellow-soft", accent: "bg-brand-yellow", status: "" },
];

function goToItems(status: string) {
  router.push({ path: "/items", query: status ? { status } : {} });
}

function getCountByKey(key: string): number {
  if (!summary.value?.counts) return 0;
  return (summary.value.counts as Record<string, number>)[key] ?? 0;
}
</script>

<template>
  <div>
    <!-- Erro de callback OAuth -->
    <div
      v-if="authError"
      class="mb-4 flex items-start gap-3 bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm"
    >
      <span class="shrink-0 font-bold mt-0.5">Erro ao conectar conta ML:</span>
      <span class="flex-1">{{ authError }}</span>
      <button class="ml-2 text-red-400 hover:text-red-600 font-bold" @click="authError = null">✕</button>
    </div>

    <div class="flex items-end justify-between mb-6 gap-3 flex-wrap">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
          Visão geral
        </p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Dashboard</h2>
      </div>
      <!-- Contas exibidas no dashboard (clique pra mostrar/ocultar) -->
      <div v-if="auth.status.authenticated && auth.accounts.length" class="flex items-center gap-2 flex-wrap">
        <button
          v-for="acc in auth.accounts"
          :key="acc.user_id"
          type="button"
          @click="toggleAccount(acc.user_id)"
          class="inline-flex items-center gap-2 text-sm font-medium px-3.5 py-1.5 rounded-full border shadow-sm transition-all"
          :class="isSelected(acc.user_id)
            ? 'bg-white text-gray-700 border-gray-200 hover:border-gray-300'
            : 'bg-gray-50 text-gray-400 border-gray-200 opacity-70 hover:opacity-100'"
          :title="isSelected(acc.user_id)
            ? 'Mostrando no dashboard — clique para ocultar'
            : 'Oculta — clique para mostrar'"
        >
          <span
            class="w-2 h-2 rounded-full"
            :class="isSelected(acc.user_id) ? 'bg-green-500 animate-pulse' : 'bg-gray-300'"
          ></span>
          {{ acc.nickname || `Conta ${acc.user_id}` }}
        </button>
      </div>
    </div>

    <!-- Not authenticated -->
    <div
      v-if="!auth.status.authenticated"
      class="bg-white rounded-2xl shadow-sm border p-10 text-center animate-fade-up"
    >
      <p class="text-lg font-bold mb-1">Nenhuma conta conectada</p>
      <p class="text-gray-500 mb-6">
        Conecte sua conta do Mercado Livre para começar.
      </p>
      <a
        href="/api/auth/login"
        class="inline-block px-6 py-3 bg-meli-blue text-brand-yellow font-semibold rounded-xl hover:bg-meli-blue-dark transition-all hover:shadow-md"
      >
        Conectar ao Mercado Livre
      </a>
    </div>

    <template v-else>
      <!-- Loading -->
      <div v-if="loading" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3 lg:gap-4 mb-6">
        <div
          v-for="i in 5"
          :key="i"
          class="bg-white rounded-2xl shadow-sm border p-5"
        >
          <div class="flex items-center justify-between mb-3">
            <div class="h-3 bg-gray-200 rounded-full w-16 animate-pulse"></div>
            <div class="h-8 w-8 bg-gray-100 rounded-xl animate-pulse"></div>
          </div>
          <div class="h-8 bg-gray-200 rounded-lg w-14 animate-pulse"></div>
        </div>
      </div>

      <template v-else>
        <!-- Bloco Faturamento / Desempenho — só pra quem tem a permissão
             'dashboard_revenue' (dado financeiro fica escondido pros demais). -->
        <div v-if="auth.can('dashboard_revenue')" class="bg-white rounded-xl shadow-sm border p-5 mb-4">
          <div class="flex items-center justify-between mb-4 gap-3 flex-wrap">
            <div class="flex items-center gap-3 flex-wrap">
              <TrendingUp :size="18" class="text-meli-blue shrink-0" />
              <!-- Toggle Faturamento / Desempenho -->
              <div class="flex items-center gap-0.5 p-0.5 bg-gray-100 rounded-lg">
                <button
                  type="button"
                  @click="chartMode = 'revenue'"
                  class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                  :class="chartMode === 'revenue' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
                >
                  Faturamento
                </button>
                <button
                  type="button"
                  @click="chartMode = 'performance'"
                  class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                  :class="chartMode === 'performance' ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
                >
                  Desempenho
                </button>
              </div>
              <!-- Subtítulo dinâmico -->
              <span v-if="chartMode === 'revenue' && revenue" class="text-sm font-normal text-gray-500">
                &mdash; total {{ revenue!.combined_total.toLocaleString("pt-BR", { style: "currency", currency: "BRL", minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                <template v-if="revenue!.combined_total_orders > 0">
                  &middot; {{ revenue!.combined_total_orders }} pedido{{ revenue!.combined_total_orders === 1 ? "" : "s" }}
                </template>
              </span>
            </div>
            <div class="flex items-center gap-2 flex-wrap">
              <!-- Presets -->
              <div class="flex items-center gap-1 p-1 bg-gray-100 rounded-lg">
                <button
                  v-for="d in [7, 15, 30, 60, 90]"
                  :key="d"
                  type="button"
                  @click="changeRevenuePeriod(d)"
                  class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                  :class="
                    !revenueFrom && !revenueTo && revenueDays === d
                      ? 'bg-white shadow-sm text-gray-900'
                      : 'text-gray-500 hover:text-gray-700'
                  "
                >
                  {{ d }}d
                </button>
              </div>
              <!-- Range customizado -->
              <div class="flex items-center gap-1 text-xs">
                <input
                  type="date"
                  v-model="revenueFrom"
                  :max="revenueTo || todayISO"
                  class="px-2 py-1 border border-gray-300 rounded-md text-xs focus:outline-none focus:ring-1 focus:ring-meli-blue"
                />
                <span class="text-gray-400">→</span>
                <input
                  type="date"
                  v-model="revenueTo"
                  :min="revenueFrom"
                  :max="todayISO"
                  class="px-2 py-1 border border-gray-300 rounded-md text-xs focus:outline-none focus:ring-1 focus:ring-meli-blue"
                />
                <button
                  type="button"
                  @click="applyCustomRange"
                  :disabled="!revenueFrom || !revenueTo"
                  class="px-2.5 py-1 rounded-md text-xs font-medium bg-meli-blue text-white hover:bg-meli-blue-dark disabled:opacity-50"
                >
                  Aplicar
                </button>
              </div>
            </div>
          </div>

          <p v-if="revenueRangeError" class="text-xs text-red-500 mb-2 text-right">
            {{ revenueRangeError }}
          </p>

          <!-- ── Modo Faturamento ── -->
          <template v-if="chartMode === 'revenue'">
            <!-- Stats agregados do período -->
            <div
              v-if="revenue && revenue!.combined_total_orders > 0"
              class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-4"
            >
              <div class="bg-gray-50 border rounded-lg px-4 py-3">
                <p class="text-[11px] uppercase tracking-wide text-gray-500 mb-1">Ticket médio</p>
                <p class="text-lg font-bold text-gray-900">
                  {{ revenue!.combined_avg_ticket.toLocaleString("pt-BR", { style: "currency", currency: "BRL", minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                </p>
                <p class="text-[11px] text-gray-400 mt-0.5">
                  {{ revenue!.combined_total.toLocaleString("pt-BR", { style: "currency", currency: "BRL", maximumFractionDigits: 0 }) }}
                  &divide; {{ revenue!.combined_total_orders }}
                  {{ revenue!.combined_total_orders === 1 ? "pedido" : "pedidos" }}
                </p>
              </div>
              <div class="bg-gray-50 border rounded-lg px-4 py-3">
                <p class="text-[11px] uppercase tracking-wide text-gray-500 mb-1">Vendas/dia (média)</p>
                <p class="text-lg font-bold text-gray-900">
                  {{ revenue!.combined_avg_orders_per_day.toLocaleString("pt-BR", { maximumFractionDigits: 2 }) }}
                </p>
                <p class="text-[11px] text-gray-400 mt-0.5">
                  em {{ revenue!.dates.length }} dia{{ revenue!.dates.length === 1 ? "" : "s" }} do período
                </p>
              </div>
              <div class="bg-gray-50 border rounded-lg px-4 py-3">
                <p class="text-[11px] uppercase tracking-wide text-gray-500 mb-1">Faturamento/dia (média)</p>
                <p class="text-lg font-bold text-gray-900">
                  {{ revenue!.combined_avg_revenue_per_day.toLocaleString("pt-BR", { style: "currency", currency: "BRL", minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                </p>
                <p class="text-[11px] text-gray-400 mt-0.5">projeção linear no período</p>
              </div>
            </div>
            <div v-if="revenueLoading" class="flex items-center justify-center py-10 text-gray-400">
              <Loader2 :size="24" class="animate-spin" />
            </div>
            <div v-else-if="!revenue || revenue!.accounts.length === 0" class="text-sm text-gray-500 text-center py-6">
              Nenhum dado de faturamento — conecte uma conta do ML.
            </div>
            <RevenueChart v-else :data="revenue!" />
          </template>

          <!-- ── Modo Desempenho ── -->
          <template v-else>
            <div v-if="performanceLoading" class="flex items-center justify-center py-10 text-gray-400">
              <Loader2 :size="24" class="animate-spin" />
            </div>
            <div v-else-if="!performance" class="text-sm text-gray-500 text-center py-6">
              Nenhum dado de desempenho — conecte uma conta do ML.
            </div>
            <PerformanceChart v-else :data="performance!" />
          </template>
        </div>
        <!-- ===== FIM BLOCO FATURAMENTO / DESEMPENHO (v-if="false") ===== -->

        <!-- Item counts -->
        <MetricStrip v-if="summary?.counts" :cols="itemCards.length" class="mb-6">
          <MetricStripItem
            v-for="card in itemCards"
            :key="card.key"
            :label="card.label"
            clickable
            @click="goToItems(card.status)"
          >
            <p class="text-3xl font-extrabold tracking-tight tabular-nums">
              {{ getCountByKey(card.key).toLocaleString("pt-BR") }}
            </p>
          </MetricStripItem>
        </MetricStrip>

        <!-- Vendas por SKU (intervalo configurável) -->
        <div class="bg-white rounded-2xl shadow-sm border p-5 mb-4 animate-fade-up">
          <div class="flex items-center justify-between mb-4 gap-3 flex-wrap">
            <h3 class="font-bold text-base flex items-center gap-2.5">
              <span class="p-1.5 rounded-lg bg-brand-yellow-soft text-gray-900">
                <ShoppingCart :size="16" />
              </span>
              Vendas por SKU
              <span v-if="salesView" class="text-sm font-medium text-gray-500 ml-1 tabular-nums">
                &mdash; {{ salesView.total_quantity }} unidade(s)
                &middot;
                {{ salesView.total_revenue.toLocaleString("pt-BR", { style: "currency", currency: "BRL" }) }}
              </span>
            </h3>
            <div class="flex items-center gap-1 p-1 bg-gray-100 rounded-xl">
              <button
                v-for="r in SALES_RANGES"
                :key="r.days"
                type="button"
                @click="changeSalesRange(r.days)"
                class="px-3 py-1 rounded-lg text-xs font-semibold transition-colors"
                :class="salesDays === r.days ? 'bg-white shadow-sm text-gray-900' : 'text-gray-500 hover:text-gray-700'"
              >
                {{ r.label }}
              </button>
            </div>
          </div>

          <div v-if="salesLoading" class="flex items-center justify-center py-8 text-gray-400">
            <Loader2 :size="20" class="animate-spin" />
          </div>
          <SalesByDayChart v-else-if="salesView" :data="salesView" :max-rows="20" />
        </div>

        <!-- Reputação -->
        <div class="mb-4 animate-fade-up">
          <h3 class="font-bold text-base mb-3 flex items-center gap-2.5">
            <span class="p-1.5 rounded-lg bg-brand-yellow-soft text-gray-900">
              <Award :size="16" />
            </span>
            Reputação das contas
          </h3>
          <div v-if="reputationLoading" class="flex items-center justify-center py-6 text-gray-400">
            <Loader2 :size="20" class="animate-spin" />
          </div>
          <div
            v-else-if="filteredReputation.length === 0"
            class="bg-white border rounded-xl p-4 text-sm text-gray-500 text-center"
          >
            Nenhuma conta selecionada.
          </div>
          <div v-else class="grid grid-cols-1 gap-3">
            <ReputationCards
              v-for="acc in filteredReputation"
              :key="acc.user_id"
              :account="acc"
            />
          </div>
        </div>

      </template>
    </template>
  </div>
</template>

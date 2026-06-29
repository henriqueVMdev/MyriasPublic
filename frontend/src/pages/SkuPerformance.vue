<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { getPerfSku, deletePerfItem, type PerfSkuResponse, type PerfSkuMember } from "@/api/performance";
import StatusBadge from "@/components/StatusBadge.vue";
import ShippingBadge from "@/components/ShippingBadge.vue";
import PromoPrice from "@/components/PromoPrice.vue";
import ModalityBreakdownChart from "@/components/ModalityBreakdownChart.vue";
import ItemAdsCard from "@/components/ItemAdsCard.vue";
import AdsOrganicLineChart from "@/components/AdsOrganicLineChart.vue";
import SelectMenu from "@/components/SelectMenu.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import MetricStripItem from "@/components/MetricStripItem.vue";
import {
  ArrowLeft, Loader2,
  Trophy, Trash2, AlertTriangle,
  ArrowDownWideNarrow, ArrowUpNarrowWide,
  ArrowDownAZ, ArrowUpAZ, Layers,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const sku = route.params.sku as string;

// Quando aberto pela seção "Anúncios Repetidos", mostra a UI de exclusão.
const allowDelete = computed(() => route.meta.allowDelete === true);
// Tem permissão pra DE FATO excluir? Sem ela, os botões aparecem cinza/travados.
const canDelete = computed(() => auth.can("delete_listing"));
const confirmingId = ref<string | null>(null);
const deletingId = ref<string | null>(null);
const deleteError = ref<string | null>(null);
const deleteNote = ref<string | null>(null);

const data = ref<PerfSkuResponse | null>(null);
const loading = ref(false);
const days = ref(90);
const errorMsg = ref<string | null>(null);

// Totais de ads do SKU (somados dos membros da CONTA filtrada) no formato do
// ItemAdsCard. Usa filteredMembers (declarado abaixo) pra acompanhar o filtro de
// conta — o getter só roda depois do setup, então a ordem de declaração é ok.
const adsTotals = computed(() => {
  const ms = filteredMembers.value;
  if (!ms.length) return null;
  let cost = 0, clicks = 0, units = 0, amount = 0;
  for (const m of ms) {
    const a = m.ads;
    if (!a) continue;
    cost += a.cost ?? 0;
    clicks += a.clicks ?? 0;
    units += a.units ?? 0;
    amount += a.amount ?? 0;
  }
  cost = Math.round(cost * 100) / 100;
  amount = Math.round(amount * 100) / 100;
  return {
    has_activity: cost > 0 || clicks > 0 || units > 0,
    cost,
    clicks,
    units,
    amount,
    acos: amount > 0 ? Math.round((cost / amount) * 10000) / 100 : null,
    cpc: clicks > 0 ? Math.round((cost / clicks) * 100) / 100 : null,
  };
});

// Filtro de conta (qual conta ver os anúncios deste SKU). Inicializa com o
// que veio da lista de Repetidos, se houver.
const accountFilter = ref<string>(
  typeof route.query.account === "string" ? route.query.account : "all"
);

// ── Seleção múltipla (modo "Anúncios Repetidos") ──
const selectedIds = ref<Set<string>>(new Set());
// Índice da última checkbox clicada — âncora pro shift+clique (seleção de intervalo).
const lastClickedIndex = ref<number | null>(null);
const bulkConfirmOpen = ref(false);
const bulkDeleting = ref(false);
const bulkProgress = ref({ done: 0, total: 0 });
const bulkSummary = ref<string | null>(null);

const PERIODS = [
  { value: 7, label: "7d" },
  { value: 30, label: "30d" },
  { value: 60, label: "60d" },
  { value: 90, label: "90d" },
];

const allMembers = computed<PerfSkuMember[]>(() => data.value?.members ?? []);
const bestId = computed(() => data.value?.best_item_id);

// Contas presentes entre os anúncios deste SKU (pra montar o seletor).
const accountOptions = computed(() => {
  const seen = new Map<number, string>();
  for (const m of allMembers.value) {
    if (!seen.has(m.user_id)) seen.set(m.user_id, m.nickname);
  }
  return {
    count: seen.size,
    options: [
      { value: "all", label: "Todas as contas" },
      ...Array.from(seen, ([id, nick]) => ({ value: String(id), label: nick })),
    ],
  };
});

// Ordenação da tabela. Padrão: mais vendas no topo (foco em achar quais
// repetidos NÃO vendem pra excluir com segurança).
type SortKey = "sales" | "visits" | "title";
const sortKey = ref<SortKey>("sales");
const sortDir = ref<"desc" | "asc">("desc");

function toggleSort(key: SortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === "desc" ? "asc" : "desc";
  } else {
    sortKey.value = key;
    // Título começa em A→Z (asc); métricas começam do maior pro menor (desc).
    sortDir.value = key === "title" ? "asc" : "desc";
  }
  // A ordenação muda os índices das linhas → âncora do shift+clique não vale mais.
  lastClickedIndex.value = null;
}

// ── Agrupar Premium + Clássico de mesmo título ──
// Quando ligado, os anúncios de mesmo título continuam aparecendo individualmente
// (cada um com suas métricas), mas são tratados como UM só na ordenação (ficam
// sempre juntos, posicionados pelo total do par) e nas checkboxes (marcar um
// marca o par inteiro). Útil porque cada produto costuma ter Premium + Clássico.
const mergeTitles = ref(false);
function normalizeTitle(t: string): string {
  return (t || "").toLowerCase().replace(/\s+/g, " ").trim();
}

// Metadados do grupo de título a que um anúncio pertence.
interface GroupMeta {
  key: string;      // título normalizado
  ids: string[];    // todos os anúncios do grupo (pra checkbox/seleção)
  size: number;
  first: boolean;   // primeiro do grupo na ordem exibida (borda/realce)
  last: boolean;    // último do grupo (borda)
  grouped: boolean; // size > 1 e agrupamento ligado
}

// Anúncios após o filtro de conta (sem agrupar nem ordenar) — base dos totais.
const filteredMembers = computed<PerfSkuMember[]>(() =>
  accountFilter.value === "all"
    ? allMembers.value
    : allMembers.value.filter((m) => String(m.user_id) === accountFilter.value)
);

// ── Gráficos reagem ao filtro de conta (agregação client-side dos membros) ──
function modalityOf(lt?: string | null): "full" | "flex" | "padrao" {
  const x = (lt || "").toLowerCase();
  if (x === "fulfillment") return "full";
  if (x === "self_service") return "flex";
  return "padrao";
}
// Vendas por modalidade só dos membros da conta filtrada (mesma forma que o backend).
const filteredByModality = computed(() => {
  const out = {
    full: { qty: 0, revenue: 0, count: 0 },
    flex: { qty: 0, revenue: 0, count: 0 },
    padrao: { qty: 0, revenue: 0, count: 0 },
  };
  for (const m of filteredMembers.value) {
    const b = out[modalityOf(m.logistic_type)];
    b.qty += m.qty_sold || 0;
    b.revenue += m.revenue || 0;
    b.count += 1;
  }
  (["full", "flex", "padrao"] as const).forEach((k) => {
    out[k].revenue = Math.round(out[k].revenue * 100) / 100;
  });
  return out;
});

// Eixo de dias + séries diárias (vendas e ads) somadas só da conta filtrada.
const chartDates = computed(() => data.value?.dates ?? []);
const filteredSalesSeries = computed(() =>
  chartDates.value.map((_, i) =>
    filteredMembers.value.reduce((s, m) => s + (m.sales_series?.[i] ?? 0), 0)
  )
);
const filteredAdsSeries = computed(() => {
  if (!filteredMembers.value.some((m) => m.ads_series)) return null;
  return chartDates.value.map((_, i) =>
    filteredMembers.value.reduce((s, m) => s + (m.ads_series?.[i] ?? 0), 0)
  );
});

// Quantos grupos de título têm mais de um anúncio (pra rotular o botão).
const mergeableGroups = computed(() => {
  const counts = new Map<string, number>();
  for (const m of filteredMembers.value) {
    const k = normalizeTitle(m.title);
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }
  return Array.from(counts.values()).filter((c) => c > 1).length;
});

// Monta a lista exibida + os metadados de grupo de cada anúncio.
// - agrupamento OFF: cada anúncio é ordenado individualmente (comportamento antigo).
// - agrupamento ON: ordena os GRUPOS pelo total agregado e mantém os membros de
//   cada grupo contíguos (e ordenados entre si pela mesma métrica).
const grouped = computed<{ list: PerfSkuMember[]; meta: Map<string, GroupMeta> }>(() => {
  const dir = sortDir.value === "desc" ? -1 : 1;
  // No modo exclusão a coluna de vendas é a vitalícia, então a ordem segue ela.
  const sales = (m: PerfSkuMember) =>
    allowDelete.value ? (m.sold_lifetime ?? m.sold_quantity ?? 0) : m.qty_sold;
  const cmp = (a: PerfSkuMember, b: PerfSkuMember) => {
    if (sortKey.value === "title")
      return (a.title || "").localeCompare(b.title || "", "pt-BR", { sensitivity: "base" }) * dir;
    if (sortKey.value === "sales") return (sales(a) - sales(b)) * dir;
    const dTotal = ((a.visits_lifetime ?? 0) - (b.visits_lifetime ?? 0)) * dir;
    if (dTotal !== 0) return dTotal;
    return ((a.visits ?? 0) - (b.visits ?? 0)) * dir;
  };

  const meta = new Map<string, GroupMeta>();

  if (!mergeTitles.value) {
    const list = [...filteredMembers.value].sort(cmp);
    for (const m of list) {
      meta.set(m.id, { key: normalizeTitle(m.title), ids: [m.id], size: 1, first: true, last: true, grouped: false });
    }
    return { list, meta };
  }

  // Agrupa por título.
  const groups = new Map<string, PerfSkuMember[]>();
  for (const m of filteredMembers.value) {
    const k = normalizeTitle(m.title);
    const arr = groups.get(k);
    if (arr) arr.push(m);
    else groups.set(k, [m]);
  }

  // Valor de ordenação do grupo = soma da métrica ativa entre seus membros.
  const groupVal = (arr: PerfSkuMember[]) => {
    if (sortKey.value === "sales") return arr.reduce((s, m) => s + sales(m), 0);
    return arr.reduce((s, m) => s + (m.visits_lifetime ?? 0), 0);
  };

  const entries = Array.from(groups.values());
  entries.sort((a, b) => {
    if (sortKey.value === "title")
      return (a[0].title || "").localeCompare(b[0].title || "", "pt-BR", { sensitivity: "base" }) * dir;
    const dv = (groupVal(a) - groupVal(b)) * dir;
    if (dv !== 0) return dv;
    // desempate pelas visitas no período do grupo
    const pa = a.reduce((s, m) => s + (m.visits ?? 0), 0);
    const pb = b.reduce((s, m) => s + (m.visits ?? 0), 0);
    return (pa - pb) * dir;
  });

  const list: PerfSkuMember[] = [];
  for (const arr of entries) {
    const sorted = [...arr].sort(cmp); // dentro do grupo, ordena pela métrica
    const ids = sorted.map((m) => m.id);
    const key = normalizeTitle(sorted[0].title);
    sorted.forEach((m, i) => {
      list.push(m);
      meta.set(m.id, {
        key,
        ids,
        size: sorted.length,
        first: i === 0,
        last: i === sorted.length - 1,
        grouped: sorted.length > 1,
      });
    });
  }
  return { list, meta };
});

const rows = computed<PerfSkuMember[]>(() => grouped.value.list);
function metaOf(id: string): GroupMeta {
  return grouped.value.meta.get(id) ?? { key: id, ids: [id], size: 1, first: true, last: true, grouped: false };
}

// Totais: usa os do backend quando "Todas", senão recalcula da conta filtrada.
const totals = computed(() => {
  if (accountFilter.value === "all") return data.value?.totals;
  const ms = filteredMembers.value;
  const total_sold = ms.reduce((s, m) => s + (m.qty_sold || 0), 0);
  const total_visits = ms.reduce((s, m) => s + (m.visits || 0), 0);
  const total_sold_lifetime = ms.reduce((s, m) => s + (m.sold_lifetime ?? m.sold_quantity ?? 0), 0);
  const total_visits_lifetime = ms.reduce((s, m) => s + (m.visits_lifetime || 0), 0);
  return {
    total_sold,
    total_sold_lifetime,
    total_revenue: ms.reduce((s, m) => s + (m.revenue || 0), 0),
    total_visits,
    total_visits_lifetime,
    total_stock: ms.reduce((s, m) => s + (m.available_quantity || 0), 0),
    // Conversão VITALÍCIA: vendas vitalícias ÷ visitas vitalícias (igual ao backend).
    conversion: total_visits_lifetime ? total_sold_lifetime / total_visits_lifetime : null,
    item_count: ms.length,
  };
});

function onAccountChange() {
  // Limpa seleção: os ids selecionados podem não estar mais visíveis.
  clearSelection();
}

// Um anúncio está "marcado" quando TODO o seu grupo de título está marcado.
function rowSelected(id: string): boolean {
  return metaOf(id).ids.every((x) => selectedIds.value.has(x));
}
function rowPartial(id: string): boolean {
  const ids = metaOf(id).ids;
  const some = ids.some((x) => selectedIds.value.has(x));
  return some && !ids.every((x) => selectedIds.value.has(x));
}
const allSelected = computed(
  () => rows.value.length > 0 && rows.value.every((m) => selectedIds.value.has(m.id))
);
const someSelected = computed(
  () => selectedIds.value.size > 0 && !allSelected.value
);

async function load() {
  loading.value = true;
  errorMsg.value = null;
  try {
    data.value = await getPerfSku(sku, days.value, allowDelete.value);
  } catch (err: any) {
    errorMsg.value = err?.response?.data?.detail || "Erro ao carregar SKU.";
    console.error("Erro ao carregar SKU:", err);
  } finally {
    loading.value = false;
  }
}

function setPeriod(d: number) {
  days.value = d;
  load();
}

function askDelete(id: string) {
  if (!canDelete.value) return;
  deleteError.value = null;
  confirmingId.value = id;
}

async function confirmDelete(m: PerfSkuMember) {
  deletingId.value = m.id;
  deleteError.value = null;
  deleteNote.value = null;
  try {
    // Exclusão avulsa (lixeira da linha): sem batch_id → vira uma linha própria
    // no histórico. Manda o SKU pra agrupar/exibir certo.
    const res = await deletePerfItem(m.id, false, { sku });
    // Remove localmente pra refletir na hora (snapshot já foi atualizado no backend)
    if (data.value) {
      data.value.members = data.value.members.filter((x) => x.id !== m.id);
    }
    confirmingId.value = null;
    if (res.action === "closed") {
      deleteNote.value =
        "O ML não permitiu deletar esse anúncio (provável que tenha pedidos). Ele foi fechado.";
    }
  } catch (err: any) {
    deleteError.value = err?.response?.data?.detail || "Erro ao excluir anúncio.";
    console.error("Erro ao excluir:", err);
  } finally {
    deletingId.value = null;
  }
}

// ── Seleção múltipla ──
// Alterna o grupo inteiro do anúncio (Premium + Clássico), ou só ele se não agrupado.
function toggleRow(id: string) {
  const s = new Set(selectedIds.value);
  const ids = metaOf(id).ids;
  const sel = ids.every((x) => s.has(x));
  for (const x of ids) sel ? s.delete(x) : s.add(x);
  selectedIds.value = s;
}

// Aplica o MESMO estado (marcar/desmarcar) a todas as linhas entre dois índices,
// inclusive as pontas (e os grupos a que elas pertencem). Usado pelo shift+clique.
function setRange(fromIdx: number, toIdx: number, select: boolean) {
  const [a, b] = fromIdx <= toIdx ? [fromIdx, toIdx] : [toIdx, fromIdx];
  const s = new Set(selectedIds.value);
  for (let i = a; i <= b; i++) {
    const m = rows.value[i];
    if (!m) continue;
    for (const id of metaOf(m.id).ids) {
      if (select) s.add(id);
      else s.delete(id);
    }
  }
  selectedIds.value = s;
}

// Clique numa checkbox de linha. Com shift e tendo uma âncora, marca/desmarca o
// intervalo inteiro com o estado que a linha clicada vai assumir. Sem shift,
// alterna só o grupo dela e vira a nova âncora.
function onMemberCheckbox(index: number, event: MouseEvent) {
  const m = rows.value[index];
  if (!m) return;
  const willSelect = !rowSelected(m.id);
  if (event.shiftKey && lastClickedIndex.value !== null) {
    setRange(lastClickedIndex.value, index, willSelect);
  } else {
    toggleRow(m.id);
  }
  lastClickedIndex.value = index;
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value
    ? new Set()
    : new Set(rows.value.map((m) => m.id));
  lastClickedIndex.value = null;
}
function clearSelection() {
  selectedIds.value = new Set();
  lastClickedIndex.value = null;
}
function askBulkDelete() {
  if (!canDelete.value || selectedIds.value.size === 0) return;
  deleteError.value = null;
  deleteNote.value = null;
  bulkSummary.value = null;
  bulkConfirmOpen.value = true;
}
async function confirmBulkDelete() {
  const ids = Array.from(selectedIds.value);
  bulkConfirmOpen.value = false;
  bulkDeleting.value = true;
  deleteError.value = null;
  deleteNote.value = null;
  bulkSummary.value = null;
  bulkProgress.value = { done: 0, total: ids.length };

  // Um batch_id pro lote inteiro: todas as exclusões deste SKU agrupam numa só
  // operação no histórico (por SKU, não por anúncio individual).
  const batchId =
    typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID()
      : `bulkdel-${Date.now()}-${Math.random().toString(36).slice(2)}`;

  let deleted = 0;
  let closed = 0;
  let failed = 0;
  for (const id of ids) {
    try {
      const res = await deletePerfItem(id, false, { sku, batchId });
      if (res.action === "closed") closed++;
      else deleted++;
      // Remove localmente pra refletir na hora
      if (data.value) {
        data.value.members = data.value.members.filter((x) => x.id !== id);
      }
      const s = new Set(selectedIds.value);
      s.delete(id);
      selectedIds.value = s;
    } catch (err) {
      failed++;
      console.error("Erro ao excluir", id, err);
    } finally {
      bulkProgress.value = { ...bulkProgress.value, done: bulkProgress.value.done + 1 };
    }
  }
  bulkDeleting.value = false;

  const parts: string[] = [];
  if (deleted) parts.push(`${deleted} excluído(s)`);
  if (closed) parts.push(`${closed} fechado(s) — o ML não permitiu excluir (provável que tenham pedidos)`);
  if (failed) parts.push(`${failed} com erro`);
  bulkSummary.value = parts.length ? parts.join(" · ") : "Nenhum anúncio processado.";
}

function fmtPrice(v: number | null): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function fmtPct(v: number | null | undefined): string {
  return v != null ? (v * 100).toFixed(2) + "%" : "—";
}
function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}
function typeLabel(lt?: string): string {
  if (lt === "gold_pro") return "Premium";
  if (lt === "gold_special") return "Clássico";
  return lt || "";
}

onMounted(load);
</script>

<template>
  <div>
    <div class="flex items-center gap-3 mb-4">
      <button @click="router.back()" class="p-2 rounded-lg hover:bg-gray-200 transition-colors">
        <ArrowLeft :size="20" />
      </button>
      <div class="flex-1 min-w-0">
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight truncate">SKU {{ sku }}</h2>
        <p class="text-sm text-gray-500">Performance agregada dos anúncios deste SKU</p>
      </div>
      <div v-if="accountOptions.count > 1" class="min-w-[170px]">
        <SelectMenu
          v-model="accountFilter"
          :options="accountOptions.options"
          size="sm"
          align="right"
          @update:model-value="onAccountChange"
        />
      </div>
      <div class="flex gap-1 p-1 bg-gray-100 rounded-xl">
        <button
          v-for="p in PERIODS" :key="p.value" @click="setPeriod(p.value)"
          class="px-3 py-1.5 rounded-lg text-sm font-semibold transition-colors"
          :class="days === p.value ? 'bg-meli-blue text-brand-yellow shadow-sm' : 'text-gray-500 hover:text-gray-700'"
        >{{ p.label }}</button>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <div v-else-if="errorMsg" class="bg-amber-50 border border-amber-200 rounded-xl p-6 text-center text-amber-800">
      {{ errorMsg }}
    </div>

    <template v-else-if="data">
      <!-- Totais -->
      <MetricStrip :cols="5" class="mb-4">
        <MetricStripItem :label="`Visitas (${days}d)`">
          <div class="text-2xl font-extrabold tabular-nums">{{ (totals?.total_visits ?? 0).toLocaleString("pt-BR") }}</div>
          <div v-if="(totals?.total_visits_lifetime ?? 0) !== (totals?.total_visits ?? 0)" class="text-[11px] text-gray-400 tabular-nums mt-0.5" title="Máximo que o ML expõe por anúncio são ~2 anos de visitas">
            {{ (totals?.total_visits_lifetime ?? 0).toLocaleString("pt-BR") }} no total (até 2 anos)
          </div>
        </MetricStripItem>
        <MetricStripItem :label="allowDelete ? 'Vendas (vitalício)' : `Vendas (${days}d)`">
          <!-- Na exclusão de repetidos: só vendas vitalícias (o "vendeu / nunca vendeu"). -->
          <template v-if="allowDelete">
            <div class="text-2xl font-extrabold text-green-600 tabular-nums">{{ (totals?.total_sold_lifetime ?? 0).toLocaleString("pt-BR") }}</div>
          </template>
          <template v-else>
            <div class="text-2xl font-extrabold text-green-600 tabular-nums">{{ totals?.total_sold ?? 0 }}</div>
            <div v-if="(totals?.total_sold_lifetime ?? 0) !== (totals?.total_sold ?? 0)" class="text-[11px] text-gray-400 tabular-nums mt-0.5">
              {{ (totals?.total_sold_lifetime ?? 0).toLocaleString("pt-BR") }} no total (vitalício)
            </div>
          </template>
        </MetricStripItem>
        <MetricStripItem label="Faturamento">
          <div class="text-2xl font-extrabold text-green-700 tabular-nums">{{ fmtPrice(totals?.total_revenue ?? 0) }}</div>
        </MetricStripItem>
        <MetricStripItem label="Conversão">
          <div class="text-2xl font-extrabold tabular-nums">{{ fmtPct(totals?.conversion) }}</div>
        </MetricStripItem>
        <MetricStripItem label="Anúncios">
          <div class="text-2xl font-extrabold tabular-nums">{{ totals?.item_count ?? 0 }}</div>
        </MetricStripItem>
      </MetricStrip>

      <!-- Vendas por modalidade de entrega (não na seção de repetidos) -->
      <ModalityBreakdownChart v-if="!allowDelete" :data="filteredByModality" class="mb-4" />

      <!-- Publicidade do SKU (somada dos anúncios da conta filtrada) -->
      <ItemAdsCard v-if="!allowDelete" :ads="adsTotals" :days="days" title="Publicidade do SKU (somado)" class="mb-4" />

      <!-- Vendas orgânicas vs por anúncios (linha por dia, somado da conta filtrada) -->
      <AdsOrganicLineChart
        v-if="!allowDelete && chartDates.length"
        :dates="chartDates"
        :sales="filteredSalesSeries"
        :ads="filteredAdsSeries"
        class="mb-4"
      />

      <div
        v-if="deleteError"
        class="bg-amber-50 border border-amber-200 rounded-xl px-4 py-2 mb-3 text-sm text-amber-800"
      >
        {{ deleteError }}
      </div>
      <div
        v-if="deleteNote"
        class="bg-blue-50 border border-blue-200 rounded-xl px-4 py-2 mb-3 text-sm text-blue-800"
      >
        {{ deleteNote }}
      </div>
      <div
        v-if="bulkSummary"
        class="bg-green-50 border border-green-200 rounded-xl px-4 py-2 mb-3 text-sm text-green-800 flex items-center justify-between gap-3"
      >
        <span>{{ bulkSummary }}</span>
        <button @click="bulkSummary = null" class="text-green-600 hover:text-green-800 font-bold">✕</button>
      </div>

      <!-- Anúncios do SKU -->
      <div class="bg-white rounded-2xl border shadow-sm overflow-hidden">
        <div class="flex items-center justify-between gap-3 px-4 py-3 border-b">
          <h3 class="text-sm font-bold text-gray-700">
            Anúncios deste SKU ({{ filteredMembers.length }})
            <span v-if="mergeTitles && mergeableGroups > 0" class="font-normal text-gray-400">· {{ mergeableGroups }} título(s) agrupado(s)</span>
          </h3>
          <div class="flex items-center gap-2">
            <!-- Agrupa Premium + Clássico de mesmo título: continuam aparecendo, mas
                 são tratados como um só na ordenação e nas checkboxes. -->
            <button
              @click="mergeTitles = !mergeTitles"
              :disabled="mergeableGroups === 0"
              class="text-xs px-3 py-1.5 rounded-lg border inline-flex items-center gap-1.5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              :class="mergeTitles
                ? 'bg-meli-blue text-brand-yellow border-meli-blue'
                : 'hover:bg-gray-50 text-gray-600'"
              :title="mergeableGroups === 0
                ? 'Nenhum título repetido pra agrupar'
                : 'Mantém os anúncios separados, mas trata os de mesmo título como um só na ordenação e nas checkboxes'"
            >
              <Layers :size="13" />
              {{ mergeTitles ? "Títulos agrupados" : "Agrupar títulos iguais" }}
              <span v-if="mergeableGroups > 0 && !mergeTitles" class="text-gray-400">({{ mergeableGroups }})</span>
            </button>
          </div>
        </div>
        <!-- Barra de ação em lote (só no modo repetidos, com algo selecionado) -->
        <div
          v-if="allowDelete && selectedIds.size > 0"
          class="flex items-center justify-end gap-2 px-4 py-2 border-b bg-red-50/40 dark:bg-red-900/10"
        >
          <div class="flex items-center gap-2">
            <span class="text-xs font-medium text-gray-500 tabular-nums">
              {{ selectedIds.size }} selecionado(s)
            </span>
            <button
              @click="clearSelection"
              :disabled="bulkDeleting"
              class="text-xs px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 transition-colors"
            >
              Limpar
            </button>
            <button
              @click="askBulkDelete"
              :disabled="bulkDeleting || !canDelete"
              :title="!canDelete ? 'Você não tem permissão para excluir anúncios' : undefined"
              class="text-xs px-3 py-1.5 rounded-lg bg-red-600 text-white font-semibold hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-red-600 inline-flex items-center gap-1.5 transition-colors"
            >
              <Loader2 v-if="bulkDeleting" :size="13" class="animate-spin" />
              <Trash2 v-else :size="13" />
              <span v-if="bulkDeleting">Excluindo {{ bulkProgress.done }}/{{ bulkProgress.total }}…</span>
              <span v-else>Excluir selecionados</span>
            </button>
          </div>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b bg-gray-50/80">
                <th v-if="allowDelete" class="pl-4 pr-1 py-2.5 w-8">
                  <input
                    type="checkbox"
                    :checked="allSelected"
                    :indeterminate.prop="someSelected"
                    @change="toggleSelectAll"
                    title="Selecionar todos"
                  />
                </th>
                <th class="px-4 py-2.5 font-bold">
                  <button
                    @click="toggleSort('title')"
                    class="inline-flex items-center gap-1 hover:text-meli-blue transition-colors uppercase tracking-wider"
                    :class="sortKey === 'title' ? 'text-meli-blue' : ''"
                    :title="sortKey === 'title' && sortDir === 'asc' ? 'Título A→Z' : 'Título Z→A'"
                  >
                    Anúncio
                    <component
                      v-if="sortKey === 'title'"
                      :is="sortDir === 'asc' ? ArrowDownAZ : ArrowUpAZ"
                      :size="13"
                    />
                  </button>
                </th>
                <th class="px-3 py-2.5 font-bold">Tipo</th>
                <th class="px-3 py-2.5 font-bold text-right">Preço</th>
                <th class="px-3 py-2.5 font-bold text-right whitespace-nowrap">
                  <button
                    @click="toggleSort('visits')"
                    class="inline-flex items-center gap-1 ml-auto hover:text-meli-blue transition-colors uppercase tracking-wider"
                    :class="sortKey === 'visits' ? 'text-meli-blue' : ''"
                    :title="sortKey === 'visits' && sortDir === 'desc' ? 'Mais visitas primeiro' : 'Menos visitas primeiro'"
                  >
                    Visitas
                    <component
                      v-if="sortKey === 'visits'"
                      :is="sortDir === 'desc' ? ArrowDownWideNarrow : ArrowUpNarrowWide"
                      :size="13"
                    />
                  </button>
                </th>
                <th class="px-3 py-2.5 font-bold text-right whitespace-nowrap">
                  <button
                    @click="toggleSort('sales')"
                    class="inline-flex items-center gap-1 ml-auto hover:text-meli-blue transition-colors uppercase tracking-wider"
                    :class="sortKey === 'sales' ? 'text-meli-blue' : ''"
                    :title="sortKey === 'sales' && sortDir === 'desc' ? 'Mais vendas primeiro' : 'Menos vendas primeiro'"
                  >
                    Vendas
                    <component
                      v-if="sortKey === 'sales'"
                      :is="sortDir === 'desc' ? ArrowDownWideNarrow : ArrowUpNarrowWide"
                      :size="13"
                    />
                  </button>
                </th>
                <th class="px-3 py-2.5 font-bold text-right">Faturamento</th>
                <th class="px-3 py-2.5 font-bold text-right">Conv.</th>
                <th v-if="!allowDelete" class="px-3 py-2.5 font-bold text-right" title="Gasto em Mercado Ads no período">Ads</th>
                <th class="px-3 py-2.5 font-bold text-right">Criado em</th>
                <th v-if="allowDelete" class="px-3 py-2.5 font-bold text-right">Excluir</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(m, index) in rows"
                :key="m.id"
                class="hover:bg-brand-yellow-soft/50 dark:hover:bg-zinc-800/60 cursor-pointer transition-colors border-l-2"
                :class="[
                  metaOf(m.id).grouped ? 'border-l-meli-blue/40' : 'border-l-transparent',
                  metaOf(m.id).grouped && !metaOf(m.id).last ? 'border-b border-dashed border-gray-100 dark:border-zinc-800' : 'border-b border-solid last:border-b-0',
                  !allowDelete && m.id === bestId ? 'bg-green-50/50' : '',
                  allowDelete && rowSelected(m.id) ? 'bg-red-50/60 dark:bg-red-900/10' : '',
                ]"
                @click="router.push(`/items/${m.id}`)"
              >
                <td v-if="allowDelete" class="pl-4 pr-1 py-2" @click.stop>
                  <input
                    type="checkbox"
                    :checked="rowSelected(m.id)"
                    :indeterminate.prop="rowPartial(m.id)"
                    :title="metaOf(m.id).grouped ? 'Anúncios do mesmo título são marcados juntos · Shift+clique seleciona um intervalo' : 'Shift+clique para selecionar um intervalo'"
                    @click="onMemberCheckbox(index, $event)"
                  />
                </td>
                <td class="px-4 py-2">
                  <div class="flex items-center gap-2 min-w-0">
                    <Trophy v-if="!allowDelete && m.id === bestId" :size="15" class="text-amber-500 flex-shrink-0" title="Melhor desempenho" />
                    <img v-if="m.thumbnail" :src="m.thumbnail" class="w-9 h-9 rounded object-cover border flex-shrink-0" />
                    <div class="min-w-0">
                      <div class="flex items-center gap-1.5 min-w-0">
                        <div class="truncate max-w-[22rem] font-medium text-gray-800" :title="m.title">{{ m.title }}</div>
                        <span
                          v-if="metaOf(m.id).grouped && metaOf(m.id).first"
                          class="flex-shrink-0 text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-meli-blue text-brand-yellow inline-flex items-center gap-0.5"
                          :title="`${metaOf(m.id).size} anúncios deste título, tratados como um só`"
                        >
                          <Layers :size="10" /> {{ metaOf(m.id).size }}
                        </span>
                      </div>
                      <div class="flex items-center gap-1.5 mt-0.5">
                        <StatusBadge :status="m.status" />
                        <ShippingBadge :logistic-type="m.logistic_type" />
                        <span class="text-[10px] text-gray-400">{{ m.nickname }}</span>
                      </div>
                    </div>
                  </div>
                </td>
                <td class="px-3 py-2">
                  <span
                    class="text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full"
                    :class="m.listing_type_id === 'gold_pro' ? 'text-purple-700 bg-purple-100' : 'text-blue-700 bg-blue-100'"
                  >{{ typeLabel(m.listing_type_id) }}</span>
                </td>
                <td class="px-3 py-2 text-right tabular-nums">
                  <PromoPrice :price="m.price" :original="m.original_price" />
                </td>
                <td class="px-3 py-2 text-right tabular-nums">
                  <div>{{ m.visits ?? "—" }}</div>
                  <div v-if="(m.visits_lifetime ?? 0) !== (m.visits ?? 0)" class="text-[10px] text-gray-400" :title="`Visitas no total (até 2 anos — limite do ML)`">
                    {{ (m.visits_lifetime ?? 0).toLocaleString("pt-BR") }} total
                  </div>
                </td>
                <td class="px-3 py-2 text-right tabular-nums font-medium">
                  <!-- Exclusão de repetidos: só vendas vitalícias. -->
                  <template v-if="allowDelete">
                    {{ (m.sold_lifetime ?? m.sold_quantity ?? 0).toLocaleString("pt-BR") }}
                  </template>
                  <template v-else>
                    <div>{{ m.qty_sold }}</div>
                    <div v-if="(m.sold_lifetime ?? m.sold_quantity ?? 0) !== m.qty_sold" class="text-[10px] text-gray-400 font-normal" :title="`Vendas no total (vitalício)`">
                      {{ (m.sold_lifetime ?? m.sold_quantity ?? 0).toLocaleString("pt-BR") }} total
                    </div>
                  </template>
                </td>
                <td class="px-3 py-2 text-right tabular-nums">{{ m.revenue ? fmtPrice(m.revenue) : "—" }}</td>
                <td class="px-3 py-2 text-right tabular-nums">{{ fmtPct(m.conversion) }}</td>
                <td v-if="!allowDelete" class="px-3 py-2 text-right tabular-nums" :class="m.ads && m.ads.cost ? 'text-purple-700 font-medium' : 'text-gray-400'">
                  {{ m.ads && m.ads.cost ? fmtPrice(m.ads.cost) : "—" }}
                </td>
                <td class="px-3 py-2 text-right tabular-nums whitespace-nowrap text-gray-600">{{ fmtDate(m.date_created) }}</td>
                <td v-if="allowDelete" class="px-3 py-2 text-right whitespace-nowrap" @click.stop>
                  <div v-if="confirmingId === m.id" class="flex items-center justify-end gap-1">
                    <button
                      @click="confirmDelete(m)"
                      :disabled="deletingId === m.id"
                      class="px-2 py-1 rounded-lg text-xs bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 inline-flex items-center gap-1"
                    >
                      <Loader2 v-if="deletingId === m.id" :size="12" class="animate-spin" />
                      Excluir
                    </button>
                    <button
                      @click="confirmingId = null"
                      :disabled="deletingId === m.id"
                      class="px-2 py-1 rounded-lg text-xs border hover:bg-gray-50 disabled:opacity-50"
                    >
                      Cancelar
                    </button>
                  </div>
                  <button
                    v-else
                    @click="askDelete(m.id)"
                    :disabled="!canDelete"
                    class="p-1.5 rounded-lg text-gray-400 transition-colors
                           hover:bg-red-50 hover:text-red-600
                           disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-gray-400"
                    :title="canDelete ? 'Excluir anúncio' : 'Você não tem permissão para excluir anúncios'"
                  >
                    <Trash2 :size="16" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- Confirmação de exclusão em lote -->
    <Teleport to="body">
      <div
        v-if="bulkConfirmOpen"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50"
        @click.self="bulkConfirmOpen = false"
      >
        <div
          class="w-full max-w-sm rounded-2xl shadow-xl overflow-hidden
                 bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700"
        >
          <div class="flex items-start gap-3 p-5">
            <div class="p-2 rounded-full bg-red-100 dark:bg-red-900/40 flex-shrink-0">
              <AlertTriangle :size="20" class="text-red-600 dark:text-red-400" />
            </div>
            <div class="min-w-0 flex-1">
              <h3 class="text-base font-bold text-gray-900 dark:text-gray-100">
                Excluir {{ selectedIds.size }} anúncio(s)?
              </h3>
              <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
                Esta ação é permanente e não pode ser desfeita. Os anúncios selecionados
                serão excluídos do Mercado Livre — ou fechados, caso já tenham pedidos.
              </p>
            </div>
          </div>
          <div class="flex justify-end gap-2 p-5 pt-0">
            <button
              @click="bulkConfirmOpen = false"
              class="px-3 py-2 rounded-lg text-sm border hover:bg-gray-50 disabled:opacity-50
                     dark:border-zinc-700 dark:hover:bg-zinc-800 dark:text-gray-200"
            >
              Cancelar
            </button>
            <button
              @click="confirmBulkDelete"
              class="px-3 py-2 rounded-lg text-sm bg-red-600 text-white font-semibold hover:bg-red-700
                     inline-flex items-center gap-1.5"
            >
              <Trash2 :size="14" />
              Excluir {{ selectedIds.size }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

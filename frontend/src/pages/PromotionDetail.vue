<script setup lang="ts">
import { ref, computed, reactive, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  getPromotionItems,
  searchPromotionItems,
  addPromotionItems,
  removePromotionItems,
  type PromotionItem,
  type PromotionItemInput,
} from "@/api/promotions";
import { useAuthStore } from "@/stores/auth";
import {
  ArrowLeft, Loader2, Tag, Trash2, Plus, PencilLine, Check,
  AlertTriangle, PackageX, Info, Search, X, CalendarClock,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canManage = computed(() => auth.can("manage_promotions"));

const promotionId = route.params.id as string;
const promotionType = (route.query.type as string) || "";
const promotionName = (route.query.name as string) || promotionId;
const startDate = (route.query.start as string) || "";
const finishDate = (route.query.finish as string) || "";
const deadlineDate = (route.query.deadline as string) || "";

function fmtDate(iso: string): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}
const dateRange = computed(() =>
  startDate || finishDate ? `${fmtDate(startDate)} – ${fmtDate(finishDate)}` : ""
);

// Tipos em que o vendedor escolhe o preço (espelha PRICE_CHOICE_TYPES do backend).
const PRICE_CHOICE = new Set(["DEAL", "LIGHTNING", "DOD", "PRICE_DISCOUNT", "PRE_NEGOTIATED"]);
const editable = PRICE_CHOICE.has(promotionType);

type TabKey = "candidate" | "started";
const tab = ref<TabKey>("candidate");

const items = ref<PromotionItem[]>([]);
const total = ref<number | null>(null);
const searchAfter = ref<string | null>(null);
const loading = ref(false);
const loadingMore = ref(false);
const errorMsg = ref<string | null>(null);

// Preço promocional digitado por anúncio (id -> valor). Só usado em tipos editáveis.
const priceInputs = reactive<Record<string, number | null>>({});

// LIGHTNING exige unidades reservadas (stock) dentro da faixa stock{min,max} do item.
const needsStock = promotionType === "LIGHTNING";
const stockInputs = reactive<Record<string, number | null>>({});
// Estoque inicial seguro dentro da faixa (ML costuma exigir > min e < max).
function defaultStock(it: PromotionItem): number | null {
  if (it.stock_min != null && it.stock_max != null) return Math.min(it.stock_min + 1, it.stock_max - 1);
  return it.stock_max ?? it.stock_min ?? null;
}
function stockInvalid(it: PromotionItem): boolean {
  if (!needsStock) return false;
  const v = stockInputs[it.id];
  if (v == null || v <= 0) return true;
  if (it.stock_min != null && v < it.stock_min) return true;
  if (it.stock_max != null && v > it.stock_max) return true;
  return false;
}
// Inválido pra incluir/atualizar: preço fora da faixa OU estoque inválido (LIGHTNING).
function itemInvalid(it: PromotionItem): boolean {
  return priceInvalid(it) || stockInvalid(it);
}

// Seleção múltipla (mesmo padrão do SkuPerformance).
const selectedIds = ref<Set<string>>(new Set());
const lastClickedIndex = ref<number | null>(null);
const bulkPct = ref<number | null>(null);
// Seleção por desconto (tipos não editáveis: ML define o preço). Marca os anúncios
// cujo desconto é <= X% e ignora os acima.
const maxSelectPct = ref<number | null>(null);
// Anúncios que o % em massa NÃO conseguiu aplicar (preço fora da faixa do ML):
// ficam desmarcados e exibem este motivo em vermelho no próprio card.
const bulkSkipped = reactive<Record<string, string>>({});
function clearBulkSkipped() {
  for (const k in bulkSkipped) delete bulkSkipped[k];
}

const confirmOpen = ref(false);
const pendingAction = ref<"add" | "update" | "remove" | null>(null);
const working = ref(false);
// Box de resposta da operação: texto + cor (info/success/partial/error) +
// lista de falhas (MLB + motivo) pro usuário entender o que não entrou.
type SummaryKind = "info" | "success" | "partial" | "error";
const summary = ref<string | null>(null);
const summaryKind = ref<SummaryKind>("info");
const summaryFails = ref<Array<{ mlb: string; reason: string }>>([]);
function showSummary(text: string | null, kind: SummaryKind = "info", fails: Array<{ mlb: string; reason: string }> = []) {
  summary.value = text;
  summaryKind.value = kind;
  summaryFails.value = fails;
}

// Cores do box conforme o resultado (espelha o pedido: verde ok, amarelo parcial, vermelho erro).
const SUMMARY_CLS: Record<SummaryKind, string> = {
  info: "bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-900/20 dark:border-blue-800 dark:text-blue-200",
  success: "bg-green-50 border-green-200 text-green-800 dark:bg-green-900/20 dark:border-green-800 dark:text-green-200",
  partial: "bg-amber-50 border-amber-200 text-amber-800 dark:bg-amber-900/20 dark:border-amber-800 dark:text-amber-200",
  error: "bg-red-50 border-red-200 text-red-800 dark:bg-red-900/20 dark:border-red-800 dark:text-red-200",
};

// Erros conhecidos do ML traduzidos; senão cai pra mensagem crua que o ML mandou.
const PROMO_ERROR_LABELS: Record<string, string> = {
  ERROR_CREDIBILITY_DISCOUNTED_PRICE: "preço promocional recusado (desconto alto demais p/ ser crível)",
};
function describeFail(r: { item_id: string; status: number; data?: any; error?: string }): { mlb: string; reason: string } {
  const d = r.data || {};
  const code = d?.cause?.[0]?.error_code || d?.error;
  const reason = PROMO_ERROR_LABELS[code] || d?.message || r.error || `erro ${r.status}`;
  return { mlb: r.item_id, reason };
}

// Busca por SKU/MLB dentro da promoção.
const searchQ = ref("");
const searchMode = ref(false);
const searching = ref(false);
const searchMessages = ref<string[]>([]);

const allSelected = computed(
  () => items.value.length > 0 && items.value.every((it) => selectedIds.value.has(it.id))
);
const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value);
const hasMore = computed(() => !!searchAfter.value);

function fmtPrice(v: number | null | undefined): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
function round2(v: number): number {
  return Math.round(v * 100) / 100;
}

// % de desconto efetivo: do que está digitado (editável) ou do promo_price (fixo).
function effectiveDiscount(it: PromotionItem): number | null {
  const promo = editable ? priceInputs[it.id] : it.promo_price;
  if (it.current_price == null || promo == null || it.current_price <= 0) return null;
  return Math.round(((it.current_price - promo) / it.current_price) * 100 * 10) / 10;
}
function priceInvalid(it: PromotionItem): boolean {
  if (!editable) return false;
  const v = priceInputs[it.id];
  if (v == null || v <= 0) return true;
  if (it.min_price != null && v < it.min_price) return true;
  if (it.max_price != null && v > it.max_price) return true;
  return false;
}
// Aviso curto, por anúncio, do porquê o preço está fora do limite (os dois lados).
function limitMessage(it: PromotionItem): string | null {
  if (!editable) return null;
  const v = priceInputs[it.id];
  if (v == null || v <= 0) return "Defina um preço promocional";
  if (it.min_price != null && v < it.min_price) return `Abaixo do mínimo (${fmtPrice(it.min_price)})`;
  if (it.max_price != null && v > it.max_price) return `Acima do máximo (${fmtPrice(it.max_price)})`;
  return null;
}

// O mesmo anúncio pode participar da campanha com vários preços (janelas distintas);
// nesse caso o ML mostra uma FAIXA. true só quando min e máx diferem de fato.
function hasPriceRange(it: PromotionItem): boolean {
  return (
    it.promo_price_min != null &&
    it.promo_price_max != null &&
    it.promo_price_max - it.promo_price_min > 0.001
  );
}
function priceRangeLabel(it: PromotionItem): string {
  return `${fmtPrice(it.promo_price_min)} – ${fmtPrice(it.promo_price_max)}`;
}

function ingest(page: { items: PromotionItem[]; paging: { total: number | null; search_after: string | null } }, append: boolean) {
  // O ML repete o mesmo MLB em várias janelas (offer_id distinto) e a paginação
  // por searchAfter é stateless, então o dedup entre páginas tem que ser aqui:
  // ao acumular, ignora ids que já estão na lista. Sem isso a aba "Participando"
  // de DEAL/LIGHTNING mostra o mesmo anúncio repetido e o contador estoura o total.
  if (append) {
    // Dup entre páginas: não adiciona de novo, mas expande a faixa de preço promo
    // do item já presente (a mesma campanha pode trazer o MLB em páginas diferentes).
    const byId = new Map(items.value.map((it) => [it.id, it]));
    const fresh: PromotionItem[] = [];
    for (const it of page.items) {
      const existing = byId.get(it.id);
      if (existing) {
        if (it.promo_price_min != null)
          existing.promo_price_min = Math.min(existing.promo_price_min ?? it.promo_price_min, it.promo_price_min);
        if (it.promo_price_max != null)
          existing.promo_price_max = Math.max(existing.promo_price_max ?? it.promo_price_max, it.promo_price_max);
      } else {
        fresh.push(it);
      }
    }
    items.value = [...items.value, ...fresh];
    for (const it of fresh) {
      priceInputs[it.id] = it.suggested_price ?? it.promo_price ?? null;
      if (needsStock) stockInputs[it.id] = defaultStock(it);
    }
  } else {
    items.value = page.items;
    for (const it of page.items) {
      priceInputs[it.id] = it.suggested_price ?? it.promo_price ?? null;
      if (needsStock) stockInputs[it.id] = defaultStock(it);
    }
  }
  total.value = page.paging.total;
  searchAfter.value = page.paging.search_after;
}

async function load() {
  loading.value = true;
  errorMsg.value = null;
  clearSelection();
  searchAfter.value = null;
  try {
    ingest(await getPromotionItems(promotionId, promotionType, tab.value), false);
  } catch (err: any) {
    errorMsg.value = err?.response?.data?.detail || "Erro ao carregar anúncios da promoção.";
    console.error("Erro ao carregar itens da promoção:", err);
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (!searchAfter.value || loadingMore.value) return;
  loadingMore.value = true;
  try {
    ingest(await getPromotionItems(promotionId, promotionType, tab.value, searchAfter.value), true);
  } catch (err: any) {
    errorMsg.value = err?.response?.data?.detail || "Erro ao carregar mais anúncios.";
  } finally {
    loadingMore.value = false;
  }
}

function setTab(t: TabKey) {
  if (tab.value === t) return;
  searchMode.value = false;
  searchQ.value = "";
  tab.value = t;
  load();
}

async function doSearch() {
  const q = searchQ.value.trim();
  if (!q) return;
  searching.value = true;
  errorMsg.value = null;
  showSummary(null);
  clearSelection();
  searchAfter.value = null;
  try {
    const res = await searchPromotionItems(promotionId, promotionType, q);
    items.value = res.items;
    total.value = res.items.length;
    searchMessages.value = res.messages || [];
    searchMode.value = true;
    for (const it of res.items) {
      priceInputs[it.id] = it.suggested_price ?? it.promo_price ?? null;
      if (needsStock) stockInputs[it.id] = defaultStock(it);
    }
  } catch (err: any) {
    errorMsg.value = err?.response?.data?.detail || "Erro ao buscar o anúncio.";
  } finally {
    searching.value = false;
  }
}

function clearSearch() {
  searchMode.value = false;
  searchQ.value = "";
  searchMessages.value = [];
  load();
}

// Badge de status do anúncio na promoção (usado no modo de busca).
function statusBadge(s: string | null): { label: string; cls: string } {
  if (s === "started") return { label: "Participando", cls: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300" };
  if (s === "candidate") return { label: "Elegível", cls: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300" };
  if (s === "pending") return { label: "Pendente", cls: "bg-gray-100 text-gray-600 dark:bg-zinc-800 dark:text-gray-300" };
  return { label: s || "—", cls: "bg-gray-100 text-gray-600 dark:bg-zinc-800 dark:text-gray-300" };
}

// ── Seleção ──
function toggleSelect(id: string) {
  delete bulkSkipped[id]; // re-interagiu com o card → o aviso antigo não vale mais
  const s = new Set(selectedIds.value);
  if (s.has(id)) s.delete(id);
  else s.add(id);
  selectedIds.value = s;
}
function setRange(fromIdx: number, toIdx: number, select: boolean) {
  const [a, b] = fromIdx <= toIdx ? [fromIdx, toIdx] : [toIdx, fromIdx];
  const s = new Set(selectedIds.value);
  for (let i = a; i <= b; i++) {
    const id = items.value[i]?.id;
    if (!id) continue;
    delete bulkSkipped[id];
    if (select) s.add(id);
    else s.delete(id);
  }
  selectedIds.value = s;
}
function onCheckbox(index: number, event: MouseEvent) {
  const id = items.value[index]?.id;
  if (!id) return;
  const willSelect = !selectedIds.value.has(id);
  if (event.shiftKey && lastClickedIndex.value !== null) {
    setRange(lastClickedIndex.value, index, willSelect);
  } else {
    toggleSelect(id);
  }
  lastClickedIndex.value = index;
}
function toggleSelectAll() {
  clearBulkSkipped();
  selectedIds.value = allSelected.value ? new Set() : new Set(items.value.map((it) => it.id));
  lastClickedIndex.value = null;
}
function clearSelection() {
  clearBulkSkipped();
  selectedIds.value = new Set();
  lastClickedIndex.value = null;
}

function applyBulkPct() {
  if (bulkPct.value == null) return;
  clearBulkSkipped();
  const pct = bulkPct.value;
  const keep = new Set(selectedIds.value);
  for (const it of items.value) {
    if (!selectedIds.value.has(it.id) || !editable || it.current_price == null) continue;
    const desired = round2(it.current_price * (1 - pct / 100));
    // Fora da faixa que o ML permite → esse % não dá pra aplicar a este anúncio:
    // desmarca e mostra o limite real (em vez de empurrar pro teto caladamente).
    if (it.min_price != null && desired < it.min_price) {
      const maxPct = Math.floor(((it.current_price - it.min_price) / it.current_price) * 100);
      bulkSkipped[it.id] = `${pct}% não permitido aqui — desconto máximo ${maxPct}%`;
      keep.delete(it.id);
    } else if (it.max_price != null && desired > it.max_price) {
      const minPct = Math.ceil(((it.current_price - it.max_price) / it.current_price) * 100);
      bulkSkipped[it.id] = `${pct}% não permitido aqui — desconto mínimo ${minPct}%`;
      keep.delete(it.id);
    } else {
      priceInputs[it.id] = desired;
    }
  }
  selectedIds.value = keep;
}

function selectByMaxDiscount() {
  if (maxSelectPct.value == null) return;
  const max = maxSelectPct.value;
  // Desconto definido pelo ML (discount_pct). Marca <= X%, ignora os acima e os sem %.
  selectedIds.value = new Set(
    items.value.filter((it) => it.discount_pct != null && it.discount_pct <= max).map((it) => it.id)
  );
  lastClickedIndex.value = null;
}

function askAction(action: "add" | "update" | "remove") {
  if (selectedIds.value.size === 0) return;
  if (action === "add" || action === "update") {
    const selected = items.value.filter((it) => selectedIds.value.has(it.id));
    const valid = selected.filter((it) => !itemInvalid(it));
    // Todos fora do limite → nada a fazer; o vermelho na linha já explica.
    if (valid.length === 0) {
      showSummary("Nenhum anúncio dentro do permitido — ajuste o que está marcado em vermelho.", "partial");
      return;
    }
  }
  showSummary(null);
  pendingAction.value = action;
  confirmOpen.value = true;
}

async function runAction() {
  const action = pendingAction.value;
  confirmOpen.value = false;
  if (!action) return;
  // Para incluir/atualizar, manda só os que estão dentro do preço permitido.
  const selected = items.value.filter(
    (it) => selectedIds.value.has(it.id) && (action === "remove" || !itemInvalid(it))
  );
  if (selected.length === 0) {
    working.value = false;
    pendingAction.value = null;
    return;
  }
  working.value = true;
  showSummary(null);
  try {
    let res;
    if (action === "remove") {
      res = await removePromotionItems({
        promotion_id: promotionId,
        promotion_type: promotionType,
        item_ids: selected.map((it) => it.id),
      });
    } else {
      const payloadItems: PromotionItemInput[] = selected.map((it) => {
        const inp: PromotionItemInput = { item_id: it.id };
        if (editable && priceInputs[it.id] != null) inp.deal_price = priceInputs[it.id]!;
        if (needsStock && stockInputs[it.id] != null) inp.stock = stockInputs[it.id]!;
        if (it.offer_id != null) inp.offer_id = it.offer_id;
        return inp;
      });
      res = await addPromotionItems({
        promotion_id: promotionId,
        promotion_type: promotionType,
        items: payloadItems,
      });
    }
    const fails = res.results.filter((r) => !r.ok).map(describeFail);
    const parts: string[] = [];
    if (res.succeeded) parts.push(`${res.succeeded} ok`);
    if (res.failed) parts.push(`${res.failed} com erro`);
    const text = parts.join(" · ") || "Nenhum anúncio processado.";
    const kind: SummaryKind = res.failed === 0 ? "success" : res.succeeded === 0 ? "error" : "partial";
    showSummary(text, res.succeeded || res.failed ? kind : "info", fails);
    clearSelection();
    // O ML é eventualmente consistente: logo após criar/remover ofertas, a
    // listagem de candidate/started ainda devolve os mesmos itens. Recarregar
    // aqui faria os incluídos reaparecerem em "Elegíveis". Então removemos da
    // lista, na hora, os que voltaram ok — mesmo padrão do removeOne().
    const okIds = new Set(res.results.filter((r) => r.ok).map((r) => r.item_id));
    if (okIds.size) {
      items.value = items.value.filter((it) => !okIds.has(it.id));
      if (total.value != null) total.value = Math.max(0, total.value - okIds.size);
    }
  } catch (err: any) {
    showSummary(err?.response?.data?.detail || "Erro ao processar a ação.", "error");
    console.error("Erro na ação de promoção:", err);
  } finally {
    working.value = false;
    pendingAction.value = null;
  }
}

// ── Edição/remoção POR LINHA (aba Participando) ──
// A aba Participando mostra o preço/desconto que JÁ está valendo (read-only).
// Pra mexer, o usuário entra em modo edição daquela linha (revela o input) e
// salva só aquele anúncio; ou tira da promo direto pelo ícone de lixeira.
const editingIds = ref<Set<string>>(new Set());
const rowWorking = ref<string | null>(null);

function isEditing(id: string): boolean {
  return editingIds.value.has(id);
}
function startEdit(it: PromotionItem) {
  priceInputs[it.id] = it.promo_price ?? it.suggested_price ?? null;
  if (needsStock && stockInputs[it.id] == null) stockInputs[it.id] = defaultStock(it);
  editingIds.value = new Set(editingIds.value).add(it.id);
}
function cancelEdit(it: PromotionItem) {
  priceInputs[it.id] = it.promo_price ?? null;
  const s = new Set(editingIds.value);
  s.delete(it.id);
  editingIds.value = s;
}

async function saveOne(it: PromotionItem) {
  if (itemInvalid(it) || !canManage.value) return;
  rowWorking.value = it.id;
  showSummary(null);
  try {
    const inp: PromotionItemInput = { item_id: it.id };
    if (priceInputs[it.id] != null) inp.deal_price = priceInputs[it.id]!;
    if (needsStock && stockInputs[it.id] != null) inp.stock = stockInputs[it.id]!;
    if (it.offer_id != null) inp.offer_id = it.offer_id;
    const res = await addPromotionItems({
      promotion_id: promotionId,
      promotion_type: promotionType,
      items: [inp],
    });
    if (res.succeeded) {
      // Atualiza a linha no lugar — sem recarregar a página inteira.
      const novo = priceInputs[it.id] ?? it.promo_price;
      it.promo_price = novo;
      it.promo_price_min = novo;
      it.promo_price_max = novo;
      it.discount_pct =
        it.current_price && novo != null && it.current_price > 0
          ? Math.round(((it.current_price - novo) / it.current_price) * 1000) / 10
          : null;
      cancelEdit(it);
      showSummary("Preço promocional atualizado.", "success");
    } else {
      showSummary("Não foi possível atualizar o preço deste anúncio.", "error", (res.results || []).filter((r) => !r.ok).map(describeFail));
    }
  } catch (err: any) {
    showSummary(err?.response?.data?.detail || "Erro ao atualizar o preço.", "error");
  } finally {
    rowWorking.value = null;
  }
}

async function removeOne(it: PromotionItem) {
  if (!canManage.value) return;
  if (!confirm(`Tirar "${it.title || it.id}" desta promoção?`)) return;
  rowWorking.value = it.id;
  showSummary(null);
  try {
    const res = await removePromotionItems({
      promotion_id: promotionId,
      promotion_type: promotionType,
      item_ids: [it.id],
    });
    if (res.succeeded) {
      items.value = items.value.filter((x) => x.id !== it.id);
      if (total.value != null) total.value -= 1;
      showSummary("Anúncio removido da promoção.", "success");
    } else {
      showSummary("Não foi possível remover este anúncio.", "error", (res.results || []).filter((r) => !r.ok).map(describeFail));
    }
  } catch (err: any) {
    showSummary(err?.response?.data?.detail || "Erro ao remover o anúncio.", "error");
  } finally {
    rowWorking.value = null;
  }
}

// O input de preço aparece quando: tipo editável E (aba Elegíveis / busca, OU a
// linha está em edição inline, OU está selecionada — pra ações em massa). Parado
// na aba Participando, a linha mostra só o preço de hoje (read-only).
function editorVisible(it: PromotionItem): boolean {
  if (!editable) return false;
  if (tab.value === "candidate" || searchMode.value) return true;
  return isEditing(it.id) || selectedIds.value.has(it.id);
}

// Desconto a exibir: com o editor aberto mostra a prévia do que foi digitado;
// senão mostra o desconto que está valendo hoje.
function displayDiscount(it: PromotionItem): number | null {
  return editorVisible(it) ? effectiveDiscount(it) : it.discount_pct;
}

// Linha tem ações próprias (alterar/remover) na aba Participando.
const showRowActions = computed(() => canManage.value && tab.value === "started" && !searchMode.value);

// Cupom só tem elegíveis (started é sempre vazio) → sem aba "Participando".
const TABS = computed<Array<{ key: TabKey; label: string }>>(() =>
  promotionType === "SELLER_COUPON_CAMPAIGN"
    ? [{ key: "candidate", label: "Elegíveis" }]
    : [
        { key: "candidate", label: "Elegíveis" },
        { key: "started", label: "Participando" },
      ]
);
const confirmLabel = computed(() =>
  pendingAction.value === "remove" ? "Remover" : pendingAction.value === "update" ? "Atualizar preço" : "Incluir"
);
// Quantos serão de fato afetados (incluir/atualizar ignora os fora do limite).
const actionCount = computed(() => {
  if (pendingAction.value === "remove") return selectedIds.value.size;
  return items.value.filter((it) => selectedIds.value.has(it.id) && !itemInvalid(it)).length;
});

// Texto explicativo do comportamento do tipo de promoção.
const typeHint = computed(() => {
  if (editable) return "Você escolhe o preço promocional de cada anúncio, dentro da faixa permitida pelo ML.";
  if (promotionType === "SELLER_COUPON_CAMPAIGN") return "Cupom de desconto percentual fixo definido pelo ML — basta incluir os anúncios.";
  return "O desconto é definido pelo Mercado Livre. Você só inclui ou remove anúncios — o preço promocional não é editável.";
});

onMounted(load);
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-center gap-3 mb-4">
      <button @click="router.back()" class="p-2 rounded-lg hover:bg-gray-200 dark:hover:bg-zinc-800 transition-colors">
        <ArrowLeft :size="20" />
      </button>
      <div class="p-2 rounded-xl bg-brand-yellow-soft dark:bg-zinc-800 flex-shrink-0">
        <Tag :size="18" class="text-brand-black dark:text-brand-yellow" />
      </div>
      <div class="flex-1 min-w-0">
        <h2 class="text-xl lg:text-2xl font-extrabold tracking-tight truncate" :title="promotionName">
          {{ promotionName }}
        </h2>
        <p class="text-sm text-gray-500 flex flex-wrap items-center gap-x-2">
          <span>{{ promotionType }}</span>
          <span v-if="dateRange" class="inline-flex items-center gap-1">
            <CalendarClock :size="13" /> {{ dateRange }}
          </span>
          <span v-if="deadlineDate" class="text-gray-400">· aderir até {{ fmtDate(deadlineDate) }}</span>
        </p>
      </div>
    </div>

    <!-- Dica sobre o tipo -->
    <div class="flex items-start gap-2 bg-gray-50 dark:bg-zinc-900/60 border dark:border-zinc-800 rounded-xl px-4 py-2.5 mb-4 text-sm text-gray-600 dark:text-gray-300">
      <Info :size="16" class="flex-shrink-0 mt-0.5 text-gray-400" />
      <span>{{ typeHint }}</span>
    </div>

    <!-- Abas + busca -->
    <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
      <div class="flex gap-1 p-1 bg-gray-100 dark:bg-zinc-800 rounded-xl w-fit">
        <button
          v-for="t in TABS"
          :key="t.key"
          @click="setTab(t.key)"
          class="px-4 py-1.5 rounded-lg text-sm font-semibold transition-colors"
          :class="tab === t.key && !searchMode ? 'bg-brand-black text-brand-yellow shadow-sm dark:bg-brand-yellow dark:text-brand-black' : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'"
        >
          {{ t.label }}
        </button>
      </div>

      <form class="relative" @submit.prevent="doSearch">
        <Search :size="15" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
        <input
          v-model="searchQ"
          type="text"
          placeholder="Buscar por SKU ou MLB…"
          class="w-64 pl-9 pr-9 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900"
        />
        <button
          v-if="searchMode || searchQ"
          type="button"
          @click="clearSearch"
          class="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded-md hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-400"
          title="Limpar busca"
        >
          <X :size="15" />
        </button>
      </form>
    </div>

    <!-- Avisos da busca (itens não encontrados / fora da promoção) -->
    <div v-if="searchMode && searchMessages.length" class="bg-amber-50 border border-amber-200 rounded-xl px-4 py-2 mb-3 text-sm text-amber-800 dark:bg-amber-900/20 dark:border-amber-800 dark:text-amber-200 space-y-0.5">
      <p v-for="(m, i) in searchMessages" :key="i">{{ m }}</p>
    </div>

    <div v-if="summary" class="border rounded-xl px-4 py-2 mb-3 text-sm" :class="SUMMARY_CLS[summaryKind]">
      <div class="flex items-center justify-between gap-3">
        <span class="font-medium">{{ summary }}</span>
        <button @click="showSummary(null)" class="font-bold hover:opacity-70">✕</button>
      </div>
      <!-- Detalhe dos que falharam: MLB + motivo, pro usuário entender e corrigir. -->
      <ul v-if="summaryFails.length" class="mt-1.5 space-y-0.5 text-[13px]">
        <li v-for="f in summaryFails" :key="f.mlb" class="flex gap-1.5">
          <span class="font-mono font-semibold flex-shrink-0">{{ f.mlb }}</span>
          <span class="opacity-90">— {{ f.reason }}</span>
        </li>
      </ul>
    </div>

    <div v-if="loading || searching" class="flex items-center justify-center py-16">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <div v-else-if="errorMsg" class="bg-amber-50 border border-amber-200 rounded-xl p-6 text-center text-amber-800">
      {{ errorMsg }}
    </div>

    <div
      v-else-if="items.length === 0"
      class="bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 p-12 text-center text-gray-500"
    >
      <PackageX :size="32" class="mx-auto mb-3 opacity-60" />
      <span v-if="searchMode">Nenhum anúncio encontrado nesta promoção para a busca.</span>
      <span v-else-if="tab === 'candidate'">Nenhum anúncio elegível para esta promoção.</span>
      <span v-else>Nenhum anúncio participando desta promoção.</span>
    </div>

    <div v-else class="bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 shadow-sm overflow-hidden">
      <!-- Barra de ações -->
      <div class="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-b dark:border-zinc-800">
        <h3 class="text-sm font-bold text-gray-700 dark:text-gray-300">
          <template v-if="searchMode">Resultados da busca</template>
          <template v-else>{{ tab === "candidate" ? "Elegíveis" : "Participando" }}</template>
          <span class="font-normal text-gray-400">
            ({{ items.length }}<span v-if="!searchMode && total != null"> de {{ total.toLocaleString("pt-BR") }}</span>)
          </span>
        </h3>

        <div v-if="selectedIds.size > 0 || !editable" class="flex flex-wrap items-center gap-2">
          <!-- Selecionar por desconto (SMART/UNHEALTHY: ML define o preço, não há % editável) -->
          <div v-if="!editable" class="flex items-center gap-1">
            <input
              v-model.number="maxSelectPct"
              type="number" min="0" max="99" placeholder="% máx"
              class="w-20 px-2 py-1.5 text-xs rounded-lg border dark:border-zinc-700 dark:bg-zinc-900"
            />
            <button
              @click="selectByMaxDiscount"
              :disabled="maxSelectPct == null"
              class="text-xs px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
              title="Selecionar anúncios com desconto até esse %"
            >
              Selecionar até {{ maxSelectPct ?? "X" }}%
            </button>
          </div>

          <template v-if="selectedIds.size > 0">
          <span class="text-xs font-medium text-gray-500 tabular-nums">{{ selectedIds.size }} selecionado(s)</span>

          <!-- Aplicar % em massa (só em tipos editáveis) -->
          <div v-if="editable" class="flex items-center gap-1">
            <input
              v-model.number="bulkPct"
              type="number" min="0" max="99" placeholder="% desc."
              class="w-20 px-2 py-1.5 text-xs rounded-lg border dark:border-zinc-700 dark:bg-zinc-900"
            />
            <button
              @click="applyBulkPct"
              :disabled="bulkPct == null"
              class="text-xs px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
              title="Aplicar este % de desconto a todos os selecionados"
            >
              Aplicar a todos
            </button>
          </div>

          <button
            @click="clearSelection"
            :disabled="working"
            class="text-xs px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            Limpar
          </button>

          <!-- Incluir: aba elegíveis ou no modo busca (upsert) -->
          <button
            v-if="searchMode || tab === 'candidate'"
            @click="askAction('add')"
            :disabled="working || !canManage"
            :title="!canManage ? 'Você não tem permissão para gerenciar promoções' : undefined"
            class="text-xs px-3 py-1.5 rounded-lg bg-brand-black text-brand-yellow font-semibold hover:opacity-90 disabled:opacity-50 inline-flex items-center gap-1.5 transition-opacity dark:bg-brand-yellow dark:text-brand-black"
          >
            <Loader2 v-if="working" :size="13" class="animate-spin" />
            <Plus v-else :size="13" />
            {{ editable ? "Incluir / atualizar" : "Incluir selecionados" }}
          </button>
          <!-- Atualizar preço: só na aba Participando de tipos editáveis -->
          <button
            v-if="!searchMode && editable && tab === 'started'"
            @click="askAction('update')"
            :disabled="working || !canManage"
            :title="!canManage ? 'Você não tem permissão para gerenciar promoções' : undefined"
            class="text-xs px-3 py-1.5 rounded-lg bg-brand-black text-brand-yellow font-semibold hover:opacity-90 disabled:opacity-50 inline-flex items-center gap-1.5 transition-opacity dark:bg-brand-yellow dark:text-brand-black"
          >
            <PencilLine :size="13" /> Atualizar preço
          </button>
          <!-- Remover: aba Participando ou modo busca -->
          <button
            v-if="searchMode || tab === 'started'"
            @click="askAction('remove')"
            :disabled="working || !canManage"
            :title="!canManage ? 'Você não tem permissão para gerenciar promoções' : undefined"
            class="text-xs px-3 py-1.5 rounded-lg bg-red-600 text-white font-semibold hover:bg-red-700 disabled:opacity-50 inline-flex items-center gap-1.5 transition-colors"
          >
            <Trash2 :size="13" /> Remover
          </button>
          </template>
        </div>
      </div>

      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-[11px] uppercase tracking-wider text-gray-500 border-b bg-gray-50/80 dark:bg-zinc-900/60 dark:border-zinc-800">
              <th class="pl-4 pr-1 py-2.5 w-8">
                <input type="checkbox" :checked="allSelected" :indeterminate.prop="someSelected" @change="toggleSelectAll" title="Selecionar todos" />
              </th>
              <th class="px-4 py-2.5 font-bold">Anúncio</th>
              <th class="px-3 py-2.5 font-bold text-right">Preço atual</th>
              <th class="px-3 py-2.5 font-bold text-right">Preço promocional</th>
              <th class="px-3 py-2.5 font-bold text-right">% desconto</th>
              <th v-if="showRowActions" class="px-3 py-2.5 font-bold text-right">Ações</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(it, index) in items"
              :key="it.offer_id || it.id"
              class="border-b last:border-0 transition-colors dark:border-zinc-800"
              :class="selectedIds.has(it.id) ? 'bg-brand-yellow-soft/60 dark:bg-zinc-800/60' : 'hover:bg-gray-50 dark:hover:bg-zinc-800/40'"
            >
              <td class="pl-4 pr-1 py-2">
                <input
                  type="checkbox"
                  :checked="selectedIds.has(it.id)"
                  title="Shift+clique para selecionar um intervalo"
                  @click="onCheckbox(index, $event)"
                />
              </td>
              <td class="px-4 py-2">
                <div class="flex items-center gap-2 min-w-0">
                  <img v-if="it.thumbnail" :src="it.thumbnail" class="w-9 h-9 rounded object-cover border flex-shrink-0 dark:border-zinc-700" />
                  <div class="min-w-0">
                    <div class="flex items-center gap-1.5 min-w-0">
                      <div class="truncate max-w-[22rem] font-medium text-gray-800 dark:text-gray-200" :title="it.title || it.id">
                        {{ it.title || it.id }}
                      </div>
                      <span
                        v-if="searchMode"
                        class="flex-shrink-0 text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-full"
                        :class="statusBadge(it.status).cls"
                      >
                        {{ statusBadge(it.status).label }}
                      </span>
                    </div>
                    <div class="text-[10px] text-gray-400">
                      <span v-if="it.sku" class="font-semibold text-gray-500 dark:text-gray-300">SKU {{ it.sku }}</span>
                      <span v-if="it.sku"> · </span>{{ it.id }}
                      <span v-if="it.stock_max != null"> · estoque promo {{ it.stock_min }}–{{ it.stock_max }}</span>
                    </div>
                    <!-- % em massa não coube na faixa do ML → anúncio foi desmarcado -->
                    <div v-if="bulkSkipped[it.id]" class="text-[10px] mt-0.5 text-red-500 font-medium flex items-center gap-1">
                      <AlertTriangle :size="11" class="flex-shrink-0" /> {{ bulkSkipped[it.id] }}
                    </div>
                  </div>
                </div>
              </td>
              <td class="px-3 py-2 text-right tabular-nums text-gray-700 dark:text-gray-300">
                {{ fmtPrice(it.current_price) }}
              </td>
              <td class="px-3 py-2 text-right tabular-nums">
                <!-- Editor (incluir/alterar preço): input + faixa permitida -->
                <div v-if="editorVisible(it)" class="flex flex-col items-end">
                  <input
                    v-model.number="priceInputs[it.id]"
                    type="number" step="0.01" min="0"
                    class="w-28 px-2 py-1 text-right rounded-lg border tabular-nums dark:bg-zinc-900"
                    :class="priceInvalid(it) ? 'border-red-400 ring-1 ring-red-300' : 'dark:border-zinc-700'"
                  />
                  <span
                    v-if="it.min_price != null || it.max_price != null"
                    class="text-[10px] mt-0.5"
                    :class="priceInvalid(it) ? 'text-red-500' : 'text-gray-400'"
                  >
                    {{ fmtPrice(it.min_price) }} – {{ fmtPrice(it.max_price) }}
                  </span>
                  <!-- Aviso de limite por anúncio (texto pequeno vermelho, sem fundo) -->
                  <span v-if="limitMessage(it)" class="text-[10px] mt-0.5 text-red-500 font-medium">
                    {{ limitMessage(it) }}
                  </span>
                  <!-- LIGHTNING: unidades reservadas (stock) — obrigatório -->
                  <template v-if="needsStock">
                    <input
                      v-model.number="stockInputs[it.id]"
                      type="number" step="1" min="1"
                      placeholder="estoque"
                      class="w-28 px-2 py-1 mt-1 text-right rounded-lg border tabular-nums dark:bg-zinc-900"
                      :class="stockInvalid(it) ? 'border-red-400 ring-1 ring-red-300' : 'dark:border-zinc-700'"
                    />
                    <span
                      v-if="it.stock_min != null || it.stock_max != null"
                      class="text-[10px] mt-0.5"
                      :class="stockInvalid(it) ? 'text-red-500' : 'text-gray-400'"
                    >
                      reservar {{ it.stock_min }}–{{ it.stock_max }} un.
                    </span>
                  </template>
                </div>
                <!-- Read-only: o preço promocional que está valendo hoje -->
                <div v-else class="flex flex-col items-end">
                  <span class="text-green-600 font-semibold">
                    <template v-if="hasPriceRange(it)">{{ priceRangeLabel(it) }}</template>
                    <template v-else>{{ fmtPrice(it.promo_price) }}</template>
                  </span>
                  <span v-if="hasPriceRange(it)" class="text-[10px] mt-0.5 text-indigo-500 font-medium" title="Este anúncio participa da campanha com mais de um preço">
                    vários preços
                  </span>
                </div>
              </td>
              <td class="px-3 py-2 text-right tabular-nums font-medium">
                <span v-if="displayDiscount(it) != null" class="text-green-600">{{ displayDiscount(it) }}%</span>
                <span v-else class="text-gray-400">—</span>
              </td>
              <!-- Ações por linha (aba Participando) -->
              <td v-if="showRowActions" class="px-3 py-2 text-right whitespace-nowrap">
                <div class="inline-flex items-center gap-1 justify-end">
                  <template v-if="isEditing(it.id)">
                    <button
                      @click="saveOne(it)"
                      :disabled="itemInvalid(it) || rowWorking === it.id"
                      title="Salvar novo preço"
                      class="p-1.5 rounded-lg bg-brand-black text-brand-yellow hover:opacity-90 disabled:opacity-50 dark:bg-brand-yellow dark:text-brand-black"
                    >
                      <Loader2 v-if="rowWorking === it.id" :size="14" class="animate-spin" />
                      <Check v-else :size="14" />
                    </button>
                    <button
                      @click="cancelEdit(it)"
                      :disabled="rowWorking === it.id"
                      title="Cancelar"
                      class="p-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                    >
                      <X :size="14" />
                    </button>
                  </template>
                  <template v-else>
                    <button
                      v-if="editable"
                      @click="startEdit(it)"
                      :disabled="rowWorking === it.id"
                      title="Alterar preço promocional"
                      class="p-1.5 rounded-lg border hover:bg-gray-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                    >
                      <PencilLine :size="14" />
                    </button>
                    <button
                      @click="removeOne(it)"
                      :disabled="rowWorking === it.id"
                      title="Tirar da promoção"
                      class="p-1.5 rounded-lg border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50 dark:border-red-900/50 dark:hover:bg-red-900/20"
                    >
                      <Loader2 v-if="rowWorking === it.id" :size="14" class="animate-spin" />
                      <Trash2 v-else :size="14" />
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Carregar mais -->
      <div v-if="hasMore" class="p-3 border-t dark:border-zinc-800 text-center">
        <button
          @click="loadMore"
          :disabled="loadingMore"
          class="text-sm px-4 py-2 rounded-lg border font-semibold hover:bg-gray-50 disabled:opacity-50 inline-flex items-center gap-2 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          <Loader2 v-if="loadingMore" :size="14" class="animate-spin" />
          Carregar mais
        </button>
      </div>
    </div>

    <!-- Confirmação -->
    <Teleport to="body">
      <div
        v-if="confirmOpen"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50"
        @click.self="confirmOpen = false"
      >
        <div class="w-full max-w-sm rounded-2xl shadow-xl overflow-hidden bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700">
          <div class="flex items-start gap-3 p-5">
            <div
              class="p-2 rounded-full flex-shrink-0"
              :class="pendingAction === 'remove' ? 'bg-red-100 dark:bg-red-900/40' : 'bg-brand-yellow-soft dark:bg-zinc-800'"
            >
              <AlertTriangle v-if="pendingAction === 'remove'" :size="20" class="text-red-600 dark:text-red-400" />
              <Tag v-else :size="20" class="text-brand-black dark:text-brand-yellow" />
            </div>
            <div class="min-w-0 flex-1">
              <h3 class="text-base font-bold text-gray-900 dark:text-gray-100">
                {{ confirmLabel }} {{ actionCount }} anúncio(s)?
              </h3>
              <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
                <template v-if="pendingAction === 'remove'">Os anúncios selecionados serão retirados desta promoção.</template>
                <template v-else>Os anúncios selecionados entrarão na promoção com o preço promocional definido.</template>
              </p>
            </div>
          </div>
          <div class="flex justify-end gap-2 p-5 pt-0">
            <button
              @click="confirmOpen = false"
              class="px-3 py-2 rounded-lg text-sm border hover:bg-gray-50 dark:border-zinc-700 dark:hover:bg-zinc-800 dark:text-gray-200"
            >
              Cancelar
            </button>
            <button
              @click="runAction"
              class="px-3 py-2 rounded-lg text-sm font-semibold inline-flex items-center gap-1.5"
              :class="pendingAction === 'remove' ? 'bg-red-600 text-white hover:bg-red-700' : 'bg-brand-black text-brand-yellow hover:opacity-90 dark:bg-brand-yellow dark:text-brand-black'"
            >
              {{ confirmLabel }} {{ actionCount }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

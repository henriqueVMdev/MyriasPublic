<script setup lang="ts">
// Popup de correção rápida da página de Anúncios Incompletos: mostra só os
// atributos faltantes (da auditoria) + descrição, e aplica em TODOS os itens
// do SKU — separados por categoria quando o SKU mistura categorias.
import { computed, onMounted, ref } from "vue";
import {
  AlertTriangle,
  Check,
  ExternalLink,
  Loader2,
  Wrench,
  X,
  XCircle,
} from "lucide-vue-next";
import {
  getItemsBySkuAllAccounts,
  getItemDescription,
  bulkUpdateMulti,
  bulkUpdateDescriptionMulti,
  type AccountItemsGroup,
  type BulkResult,
} from "@/api/bulk";
import { getItem, getCategoryAttributes, type CategoryAttribute } from "@/api/items";
import { getQualityReport } from "@/api/quality";
import { initAttrValues, type AttrValue } from "@/lib/attrValues";
import { useAuthStore } from "@/stores/auth";
import type { MeliItem } from "@/types/item";
import AttributeInput from "@/components/AttributeInput.vue";

const props = defineProps<{ sku: string }>();
const emit = defineEmits<{ close: []; saved: [itemIds: string[]] }>();

const auth = useAuthStore();
const canEdit = computed(() => auth.can("bulk_edit"));

const loading = ref(true);
const saving = ref(false);
const loadError = ref("");
const saveErrors = ref<Array<{ item_id: string; message: string }>>([]);

const groups = ref<AccountItemsGroup[]>([]);
// item_id -> attribute_ids faltantes segundo a auditoria (conta ativa)
const missingByItem = ref<Record<string, Set<string>>>({});
const needsDescription = ref(false);
// Issues que não dá pra corrigir aqui (clipe/fotos) — só aviso
const manualIssueLabels = ref<string[]>([]);

const descriptionValue = ref("");
const descriptionTouched = ref(false);

interface CatState {
  loading: boolean;
  attrs: CategoryAttribute[]; // já filtrados aos faltantes
  values: Record<string, AttrValue>;
}
const catState = ref<Record<string, CatState>>({});
const activeCategoryId = ref("");

const allItems = computed<MeliItem[]>(() => groups.value.flatMap((g) => g.items));

const itemsByCategory = computed(() => {
  const map = new Map<string, MeliItem[]>();
  for (const item of allItems.value) {
    if (!item.category_id) continue;
    if (!map.has(item.category_id)) map.set(item.category_id, []);
    map.get(item.category_id)!.push(item);
  }
  return map;
});

// Categorias do SKU com os atributos faltantes (união dos itens da categoria)
const categories = computed(() =>
  Array.from(itemsByCategory.value.entries())
    .map(([categoryId, items]) => {
      const missing = new Set<string>();
      for (const item of items) {
        for (const attrId of missingByItem.value[item.id] || []) missing.add(attrId);
      }
      return { categoryId, items, missingAttrIds: missing };
    })
    .filter((cat) => cat.missingAttrIds.size > 0)
);

const hasMultipleCategories = computed(() => categories.value.length > 1);
const activeState = computed(() => catState.value[activeCategoryId.value]);

function categoryFilled(catId: string): boolean {
  const st = catState.value[catId];
  return !!st && buildChangedAttrs(st).length > 0;
}

const affectedItemIds = computed(() => {
  const ids = new Set<string>();
  for (const cat of categories.value) {
    if (!categoryFilled(cat.categoryId)) continue;
    for (const item of cat.items) ids.add(item.id);
  }
  if (descriptionTouched.value && descriptionValue.value.trim()) {
    for (const item of allItems.value) ids.add(item.id);
  }
  return ids;
});

const canSave = computed(() => canEdit.value && affectedItemIds.value.size > 0 && !saving.value);

async function load() {
  loading.value = true;
  loadError.value = "";
  try {
    const [g, report] = await Promise.all([
      getItemsBySkuAllAccounts(props.sku),
      // q= faz busca por substring — filtra o SKU exato abaixo
      getQualityReport({ q: props.sku, limit: 100 }),
    ]);
    groups.value = g;

    const missing: Record<string, Set<string>> = {};
    const manual = new Set<string>();
    for (const row of report.items) {
      if (row.sku !== props.sku) continue;
      for (const issue of row.issues) {
        if (issue.type === "attribute" && issue.attribute_id) {
          if (!missing[row.id]) missing[row.id] = new Set();
          missing[row.id].add(issue.attribute_id);
        } else if (issue.key === "description") {
          needsDescription.value = true;
        } else if (issue.type === "clip" || issue.type === "picture") {
          manual.add(issue.label);
        }
      }
    }
    missingByItem.value = missing;
    manualIssueLabels.value = Array.from(manual);

    if (needsDescription.value && allItems.value.length) {
      try {
        descriptionValue.value = await getItemDescription(allItems.value[0].id);
      } catch {
        descriptionValue.value = "";
      }
    }

    const first = categories.value[0];
    if (first) await selectCategory(first.categoryId);
  } catch (err: any) {
    loadError.value = err?.response?.data?.detail || "Não foi possível carregar os anúncios do SKU.";
  } finally {
    loading.value = false;
  }
}

async function selectCategory(catId: string) {
  activeCategoryId.value = catId;
  if (catState.value[catId]) return;
  const cat = categories.value.find((c) => c.categoryId === catId);
  if (!cat) return;
  catState.value = { ...catState.value, [catId]: { loading: true, attrs: [], values: {} } };
  try {
    // Multi-get do ML às vezes omite attributes — rebusca individual se vazio
    let refItem: MeliItem | undefined = cat.items.find((i) => i.attributes?.length);
    if (!refItem) {
      try {
        refItem = await getItem(cat.items[0].id);
      } catch {
        refItem = cat.items[0];
      }
    }
    const all = await getCategoryAttributes(catId);
    const attrs = all.filter((a) => cat.missingAttrIds.has(a.id));
    const values = initAttrValues(attrs, refItem?.attributes || []);
    catState.value = { ...catState.value, [catId]: { loading: false, attrs, values } };
  } catch {
    catState.value = { ...catState.value, [catId]: { loading: false, attrs: [], values: {} } };
    loadError.value = "Não foi possível carregar os atributos da categoria.";
  }
}

// ponytail: os campos são os faltantes — envia o que foi preenchido, sem diff
// contra o valor original (reenviar valor igual é inofensivo pro ML).
function buildChangedAttrs(st: CatState): Record<string, unknown>[] {
  const changed: Record<string, unknown>[] = [];
  for (const attr of st.attrs) {
    const v = st.values[attr.id];
    if (!v) continue;
    if (attr.value_type === "number_unit") {
      if (v.number === null || v.number === undefined) continue;
      changed.push({
        id: attr.id,
        value_struct: { number: v.number, unit: v.unit || "" },
        value_name: v.unit ? `${v.number} ${v.unit}` : String(v.number),
      });
      continue;
    }
    // value_type "number": ML rejeita texto com 400 que derruba o item inteiro
    if (attr.value_type === "number") {
      const raw = v.value_name;
      if (raw === null || raw === undefined || String(raw).trim() === "") continue;
      const n = Number(String(raw).replace(",", "."));
      if (!Number.isFinite(n)) continue;
      changed.push({ id: attr.id, value_name: String(n) });
      continue;
    }
    if (!v.value_id && !v.value_name) continue;
    const entry: Record<string, unknown> = { id: attr.id };
    if (v.value_id) entry.value_id = v.value_id;
    if (v.value_name) entry.value_name = v.value_name;
    changed.push(entry);
  }
  return changed;
}

function buildPayload(items: MeliItem[]) {
  const byAccount = new Map<number, string[]>();
  for (const g of groups.value) {
    for (const item of g.items) {
      if (!items.some((i) => i.id === item.id)) continue;
      if (!byAccount.has(g.user_id)) byAccount.set(g.user_id, []);
      byAccount.get(g.user_id)!.push(item.id);
    }
  }
  return Array.from(byAccount.entries()).map(([user_id, item_ids]) => ({ user_id, item_ids }));
}

function formatError(err: unknown): string {
  if (typeof err === "string") return err;
  const e = err as Record<string, unknown> | null;
  if (e?.message) return String(e.message);
  try {
    return JSON.stringify(err);
  } catch {
    return "Erro desconhecido";
  }
}

async function save() {
  if (!canSave.value) return;
  saving.value = true;
  saveErrors.value = [];
  const batchId = crypto.randomUUID();
  const titles: Record<string, string> = {};
  for (const item of allItems.value) if (item.title) titles[item.id] = item.title;

  const okIds = new Set<string>();
  const failed = new Set<string>();

  function collect(res: BulkResult, sentIds: string[]) {
    for (const e of res.errors) {
      failed.add(e.item_id);
      saveErrors.value.push({ item_id: e.item_id, message: formatError(e.error) });
    }
    for (const id of sentIds) if (!failed.has(id)) okIds.add(id);
  }

  try {
    for (const cat of categories.value) {
      const st = catState.value[cat.categoryId];
      if (!st) continue;
      const changed = buildChangedAttrs(st);
      if (!changed.length) continue;
      const payload = buildPayload(cat.items);
      const res = await bulkUpdateMulti(payload, { attributes: changed }, props.sku, { titles, batchId });
      collect(res, cat.items.map((i) => i.id));
    }

    if (descriptionTouched.value && descriptionValue.value.trim()) {
      const payload = buildPayload(allItems.value);
      const res = await bulkUpdateDescriptionMulti(payload, descriptionValue.value.trim(), props.sku, batchId);
      collect(res, allItems.value.map((i) => i.id));
    }

    if (okIds.size) emit("saved", Array.from(okIds));
    if (!saveErrors.value.length) emit("close");
  } catch (err: any) {
    saveErrors.value.push({
      item_id: "",
      message: err?.response?.data?.detail || "Falha ao aplicar as alterações.",
    });
  } finally {
    saving.value = false;
  }
}

function itemLabel(itemId: string): string {
  const item = allItems.value.find((i) => i.id === itemId);
  return item?.title || itemId;
}

onMounted(load);
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50" @click.self="emit('close')">
    <div class="w-full max-w-2xl max-h-[90vh] flex flex-col rounded-2xl bg-white shadow-xl">
      <!-- Header -->
      <div class="flex items-center justify-between gap-3 px-5 py-4 border-b">
        <div class="flex items-center gap-2.5 min-w-0">
          <span class="w-9 h-9 rounded-xl bg-meli-blue text-brand-yellow flex items-center justify-center flex-shrink-0">
            <Wrench :size="17" />
          </span>
          <div class="min-w-0">
            <h3 class="font-bold truncate">Corrigir pendências</h3>
            <p class="text-xs text-gray-500">
              SKU {{ sku }}<template v-if="!loading"> · {{ allItems.length }} anúncio(s) em {{ groups.length }} conta(s)</template>
            </p>
          </div>
        </div>
        <button class="p-2 rounded-lg hover:bg-gray-100" @click="emit('close')"><X :size="18" /></button>
      </div>

      <!-- Body -->
      <div class="flex-1 overflow-y-auto p-5 space-y-4">
        <div v-if="loading" class="py-12 flex items-center justify-center gap-2 text-gray-500">
          <Loader2 :size="20" class="animate-spin" /> Carregando anúncios do SKU...
        </div>

        <div v-else-if="loadError" class="flex items-center gap-2 px-4 py-3 rounded-xl border border-red-200 bg-red-50 text-red-700 text-sm">
          <AlertTriangle :size="17" class="flex-shrink-0" /> {{ loadError }}
        </div>

        <template v-else>
          <!-- Seletor de categoria: aparece quando o SKU mistura categorias -->
          <div v-if="hasMultipleCategories" class="rounded-xl border bg-amber-50/60 border-amber-200 p-3">
            <p class="text-xs font-bold text-amber-800 mb-2">
              Este SKU tem anúncios em categorias diferentes — escolha qual está alterando:
            </p>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="cat in categories"
                :key="cat.categoryId"
                type="button"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-xs font-semibold transition-colors"
                :class="activeCategoryId === cat.categoryId
                  ? 'bg-meli-blue text-brand-yellow border-meli-blue shadow-sm'
                  : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'"
                @click="selectCategory(cat.categoryId)"
              >
                <Check v-if="activeCategoryId === cat.categoryId" :size="12" />
                {{ cat.categoryId }}
                <span class="opacity-75 font-normal">· {{ cat.items.length }} anúncio(s)</span>
                <span
                  v-if="categoryFilled(cat.categoryId)"
                  class="w-1.5 h-1.5 rounded-full"
                  :class="activeCategoryId === cat.categoryId ? 'bg-brand-yellow dark:bg-brand-black' : 'bg-emerald-500'"
                  title="Tem alterações preenchidas"
                ></span>
              </button>
            </div>
            <p class="text-[11px] text-amber-700/80 mt-2">
              Os atributos abaixo são aplicados apenas aos anúncios da categoria marcada.
            </p>
          </div>

          <!-- Atributos faltantes da categoria ativa -->
          <div v-if="activeState" class="rounded-xl border p-4">
            <div class="flex items-center justify-between gap-2 mb-3">
              <h4 class="text-sm font-bold">Atributos faltantes</h4>
              <span class="text-[11px] text-gray-400 font-mono">{{ activeCategoryId }}</span>
            </div>
            <div v-if="activeState.loading" class="py-6 flex items-center justify-center gap-2 text-gray-500 text-sm">
              <Loader2 :size="16" class="animate-spin" /> Carregando atributos...
            </div>
            <div v-else-if="!activeState.attrs.length" class="text-sm text-gray-400 py-2">
              O Mercado Livre não retornou definição editável para os atributos pendentes desta categoria.
            </div>
            <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div v-for="attr in activeState.attrs" :key="attr.id">
                <label class="block text-xs font-medium text-gray-700 mb-1" :title="attr.tooltip || undefined">
                  {{ attr.name }}
                  <span v-if="attr.tags.required || attr.tags.catalog_required" class="text-red-400 ml-0.5">*</span>
                </label>
                <AttributeInput
                  :attr="attr"
                  :model-value="activeState.values[attr.id]"
                  @update:model-value="(v) => (activeState!.values[attr.id] = v)"
                />
              </div>
            </div>
          </div>

          <div v-else-if="!categories.length && !needsDescription" class="text-sm text-gray-500 py-2">
            Nenhum atributo pendente editável para este SKU.
          </div>

          <!-- Descrição -->
          <div v-if="needsDescription" class="rounded-xl border p-4">
            <h4 class="text-sm font-bold mb-1">Descrição</h4>
            <p class="text-xs text-gray-500 mb-2">Aplicada como texto simples a todos os anúncios do SKU.</p>
            <textarea
              v-model="descriptionValue"
              @input="descriptionTouched = true"
              rows="5"
              placeholder="Descrição do anúncio..."
              class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue resize-y"
              :class="descriptionTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
            />
          </div>

          <!-- Pendências sem correção via API (clipe/fotos) -->
          <div v-if="manualIssueLabels.length" class="flex items-start gap-2 px-4 py-3 rounded-xl border border-amber-200 bg-amber-50 text-amber-800 text-xs">
            <AlertTriangle :size="15" class="flex-shrink-0 mt-0.5" />
            <div>
              <p class="font-bold">{{ manualIssueLabels.join(" · ") }}</p>
              <p class="mt-0.5">
                Esses ajustes só podem ser feitos direto no Mercado Livre.
                <a
                  v-if="allItems[0]?.permalink"
                  :href="allItems[0].permalink"
                  target="_blank"
                  rel="noopener"
                  class="inline-flex items-center gap-1 font-semibold underline"
                >Abrir anúncio <ExternalLink :size="11" /></a>
              </p>
            </div>
          </div>

          <!-- Erros do salvar -->
          <div v-if="saveErrors.length" class="rounded-xl border border-red-200 bg-red-50 px-4 py-3 space-y-1">
            <p v-for="(err, i) in saveErrors" :key="i" class="text-xs text-red-700 flex items-start gap-1.5">
              <XCircle :size="13" class="flex-shrink-0 mt-0.5" />
              <span><template v-if="err.item_id">{{ itemLabel(err.item_id) }} — </template>{{ err.message }}</span>
            </p>
          </div>
        </template>
      </div>

      <!-- Footer -->
      <div class="flex items-center justify-between gap-3 px-5 py-4 border-t">
        <p class="text-xs text-gray-500">
          <template v-if="!loading && affectedItemIds.size">
            Vai aplicar a {{ affectedItemIds.size }} anúncio(s)
          </template>
        </p>
        <div class="flex items-center gap-2">
          <button class="px-4 py-2.5 rounded-xl border text-sm font-semibold hover:bg-gray-50" @click="emit('close')">
            Cancelar
          </button>
          <button
            class="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-meli-blue text-brand-yellow text-sm font-semibold hover:bg-meli-blue-dark disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            :disabled="!canSave"
            :title="canEdit ? '' : 'Você não tem permissão para edição em massa'"
            @click="save"
          >
            <Loader2 v-if="saving" :size="15" class="animate-spin" />
            {{ saving ? "Aplicando..." : "Aplicar correções" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

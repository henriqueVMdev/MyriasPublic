<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  getItemsBySkuAllAccounts,
  bulkUpdateMulti,
  bulkUpdateDescriptionMulti,
  getItemCompatibilities,
  bulkUpdateCompatibilities,
  getPositionAttributes,
  getItemPackageInfo,
  getItemDescription,
  bulkUpdatePositions,
  type PositionAttribute,
  type BulkResult,
  type AccountItemsGroup,
  type VehicleCompatibility,
  type ItemCompatibility,
  type ItemPackageInfo,
} from "@/api/bulk";
import { uploadPicture, getCategoryAttributes, type CategoryAttribute } from "@/api/items";
import { initAttrValues, attrValueFilled, type AttrValue } from "@/lib/attrValues";
import { useAuthStore } from "@/stores/auth";
import type { MeliItem } from "@/types/item";
import AttributeInput from "@/components/AttributeInput.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import ListingTypeBadge from "@/components/ListingTypeBadge.vue";
import ShippingBadge from "@/components/ShippingBadge.vue";
import PositionSelect from "@/components/PositionSelect.vue";
import SelectMenu from "@/components/SelectMenu.vue";
import {
  ArrowLeft,
  Loader2,
  CheckCircle,
  XCircle,
  Check,
  Plus,
  Trash2,
  GripVertical,
  Image,
  Settings,
  Package,
  Ruler,
  Users,
  Car,
  PauseCircle,
  PlayCircle,
  X,
  Lock,
} from "lucide-vue-next";

const route = useRoute();
const router = useRouter();
const sku = decodeURIComponent(route.params.sku as string);

const groups = ref<AccountItemsGroup[]>([]);
const selectedIds = ref<Set<string>>(new Set());
const loading = ref(true);
const saving = ref(false);
const result = ref<BulkResult | null>(null);
const statusSaving = ref(false);
const statusResult = ref<BulkResult | null>(null);

const activeTab = ref<"basic" | "attributes" | "pictures" | "compatibility">("basic");

const fields = ref({
  title: { value: "" },
  price: { value: 0 },
  available_quantity: { value: 0 },
  seller_custom_field: { value: "" },
});

// Snapshot dos valores iniciais (carregados do primeiro item) — referência visual
// do "ponto de partida". O envio é controlado por `touchedFields`, não pela diff:
// se o usuário tocou no campo, manda — mesmo que o valor coincida com o inicial.
const initialFields = ref({
  title: "",
  price: 0,
  available_quantity: 0,
  seller_custom_field: "",
});

// Descrição — subresource separado no ML (PUT /items/{id}/description)
const descriptionValue = ref("");
const descriptionTouched = ref(false);
const loadingDescription = ref(false);

// Medidas de embalagem editáveis — enviadas como seller_package_* attributes
interface PackageDimField { value: number | null; unit: string; }
const packageDims = ref({
  height: { value: null as number | null, unit: "cm" } as PackageDimField,
  width:  { value: null as number | null, unit: "cm" } as PackageDimField,
  length: { value: null as number | null, unit: "cm" } as PackageDimField,
  weight: { value: null as number | null, unit: "g"  } as PackageDimField,
});
const packageDimsTouched = ref(false);

// Campos que o usuário editou explicitamente (via @input). Só esses vão no PUT.
const touchedFields = ref<Set<string>>(new Set());
function markTouched(field: string) {
  touchedFields.value = new Set(touchedFields.value).add(field);
}

// Medidas de envio — somente leitura (ML não aceita alteração via API para a maioria dos itens).
// Populadas do primeiro item carregado: tenta PACKAGE_* attrs, depois shipping.dimensions.
interface ShippingDisplay {
  height: number | null;
  width: number | null;
  length: number | null;
  weight: number | null;
  heightUnit: string;
  widthUnit: string;
  lengthUnit: string;
  weightUnit: string;
  source: "package_attrs" | "shipping_dimensions" | null;
}
// Dados de embalagem do primeiro item — populados via endpoint individual
// (o multiget do ML não retorna value_struct nem shipping.dimensions de forma confiável).
const firstItemPkgInfo = ref<ItemPackageInfo | null>(null);

function parseShippingDimensions(dim: string): { h: number | null; w: number | null; l: number | null; wt: number | null } {
  const m = dim.match(/^(\d+(?:[.,]\d+)?)x(\d+(?:[.,]\d+)?)x(\d+(?:[.,]\d+)?),(\d+(?:[.,]\d+)?)$/);
  if (!m) return { h: null, w: null, l: null, wt: null };
  return {
    h: parseFloat(m[1]),
    w: parseFloat(m[2]),
    l: parseFloat(m[3]),
    wt: parseFloat(m[4]),
  };
}

const currentShipping = computed((): ShippingDisplay => {
  const empty: ShippingDisplay = { height: null, width: null, length: null, weight: null, heightUnit: "cm", widthUnit: "cm", lengthUnit: "cm", weightUnit: "g", source: null };
  const pkg = firstItemPkgInfo.value;
  if (!pkg) return empty;

  // 1) PACKAGE_* attributes — retornados pelo endpoint individual com value_struct
  if (pkg.package_attributes.length > 0) {
    const map: Record<string, { number: number | null; unit: string }> = {};
    for (const a of pkg.package_attributes) {
      const num = a.value_struct?.number ?? (() => {
        const n = parseFloat(String(a.value_name ?? "").replace(",", "."));
        return isNaN(n) ? null : n;
      })();
      const unit = a.value_struct?.unit ?? "";
      map[a.id] = { number: num, unit };
    }
    const h = map["PACKAGE_HEIGHT"]?.number ?? null;
    const w = map["PACKAGE_WIDTH"]?.number ?? null;
    const l = map["PACKAGE_LENGTH"]?.number ?? null;
    const wt = map["PACKAGE_WEIGHT"]?.number ?? null;
    if (h !== null || w !== null || l !== null || wt !== null) {
      return {
        height: h, width: w, length: l, weight: wt,
        heightUnit: map["PACKAGE_HEIGHT"]?.unit || "cm",
        widthUnit:  map["PACKAGE_WIDTH"]?.unit  || "cm",
        lengthUnit: map["PACKAGE_LENGTH"]?.unit || "cm",
        weightUnit: map["PACKAGE_WEIGHT"]?.unit || "g",
        source: "package_attrs",
      };
    }
  }

  // 2) Fallback: shipping.dimensions ("HxWxL,Peso")
  if (pkg.shipping_dimensions) {
    const p = parseShippingDimensions(pkg.shipping_dimensions);
    if (p.h !== null) {
      return { height: p.h, width: p.w, length: p.l, weight: p.wt, heightUnit: "cm", widthUnit: "cm", lengthUnit: "cm", weightUnit: "g", source: "shipping_dimensions" };
    }
  }

  return empty;
});

// Fotos: só envia se o usuário modificou (add/remove/reorder/upload).
// Apenas "carregar fotos do primeiro" não dispara — é só pré-visualização.
const bulkPictures = ref<Array<{ id?: string; source?: string; preview?: string }>>([]);
const newPicUrl = ref("");
const draggingPicIndex = ref<number | null>(null);
const uploadingPic = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const picturesTouched = ref(false);
const keepCoverPhoto = ref(false);
const picturesEnabled = computed(() => picturesTouched.value && bulkPictures.value.length > 0);

const packageDimsEnabled = computed(() => {
  if (!packageDimsTouched.value) return false;
  const d = packageDims.value;
  return d.height.value !== null || d.width.value !== null || d.length.value !== null || d.weight.value !== null;
});

// Atributos: ativo automaticamente se algum atributo foi setado
const categoryAttrs = ref<CategoryAttribute[]>([]);
const attrValues = ref<Record<string, AttrValue>>({});
// Attrs marcados explicitamente como "Não se aplica" pelo usuário
const attrNotApplicable = ref<Set<string>>(new Set());
const loadingAttrs = ref(false);

// --- Multi-category attr support ---
interface PerCategoryAttrState {
  loaded: boolean;
  categoryAttrs: CategoryAttribute[];
  attrValues: Record<string, AttrValue>;
  attrNotApplicable: Set<string>;
}
const attrStateByCategory = ref<Record<string, PerCategoryAttrState>>({});
const activeAttrCategoryId = ref<string>("");

function categoryHasAttrValues(state: { attrValues: Record<string, AttrValue>; attrNotApplicable: Set<string> }): boolean {
  return state.attrNotApplicable.size > 0 ||
    Object.values(state.attrValues).some((v) => (v.value_id && v.value_id.length > 0) || (v.value_name && v.value_name.length > 0));
}

// True se QUALQUER categoria tem attrs preenchidos (para canSave e confirmação)
const attrsEnabled = computed(() => {
  if (categoryHasAttrValues({ attrValues: attrValues.value, attrNotApplicable: attrNotApplicable.value })) return true;
  for (const [catId, state] of Object.entries(attrStateByCategory.value)) {
    if (catId === activeAttrCategoryId.value || !state.loaded) continue;
    if (categoryHasAttrValues(state)) return true;
  }
  return false;
});

// Flat list of all items across accounts
const allItems = computed<MeliItem[]>(() => groups.value.flatMap((g) => g.items));
const totalItems = computed(() => allItems.value.length);
const accountsWithItems = computed(() => groups.value.filter((g) => g.items.length > 0));

// Map item_id → user_id to build save payload
const itemAccountMap = computed<Map<string, number>>(() => {
  const map = new Map<string, number>();
  for (const g of groups.value) {
    for (const it of g.items) map.set(it.id, g.user_id);
  }
  return map;
});

// Items agrupados por category_id — base para a separação de atributos
const itemsByCategoryId = computed(() => {
  const map = new Map<string, MeliItem[]>();
  for (const item of allItems.value) {
    if (!item.category_id) continue;
    if (!map.has(item.category_id)) map.set(item.category_id, []);
    map.get(item.category_id)!.push(item);
  }
  return map;
});

const distinctCategories = computed(() =>
  Array.from(itemsByCategoryId.value.entries()).map(([categoryId, items]) => ({
    categoryId,
    itemCount: items.length,
  }))
);

const hasMultipleCategories = computed(() => distinctCategories.value.length > 1);

function buildPayloadForItems(itemIds: string[]) {
  const map = itemAccountMap.value;
  const byAccount = new Map<number, string[]>();
  for (const id of itemIds) {
    const uid = map.get(id);
    if (uid === undefined) continue;
    if (!byAccount.has(uid)) byAccount.set(uid, []);
    byAccount.get(uid)!.push(id);
  }
  return Array.from(byAccount.entries()).map(([user_id, item_ids]) => ({ user_id, item_ids }));
}

// Valores ATUAIS (antes da edição) dos campos escalares editados, por item.
// O histórico usa pra mostrar "antes → depois" (ex.: status paused → ativo).
// Só campos escalares — atributos/fotos não têm "antes" simples.
function buildBeforeMap(changedFields: string[]): Record<string, Record<string, unknown>> {
  const SCALAR = new Set(["title", "price", "available_quantity", "seller_custom_field", "status"]);
  const wanted = changedFields.filter((f) => SCALAR.has(f));
  const out: Record<string, Record<string, unknown>> = {};
  if (!wanted.length) return out;
  for (const it of allItems.value) {
    if (!selectedIds.value.has(it.id)) continue;
    const b: Record<string, unknown> = {};
    for (const f of wanted) {
      if (f === "seller_custom_field") b[f] = extractItemSku(it);
      else if (f === "status") b[f] = it.status;
      else b[f] = (it as unknown as Record<string, unknown>)[f];
    }
    out[it.id] = b;
  }
  return out;
}

// --- Pictures ---
function addPicByUrl() {
  const url = newPicUrl.value.trim();
  if (!url) return;
  bulkPictures.value.push({ source: url, preview: url });
  newPicUrl.value = "";
  picturesTouched.value = true;
}
function removePic(index: number) {
  bulkPictures.value.splice(index, 1);
  picturesTouched.value = true;
}
function onPicDragStart(index: number) { draggingPicIndex.value = index; }
function onPicDragOver(e: DragEvent, index: number) {
  e.preventDefault();
  if (draggingPicIndex.value === null || draggingPicIndex.value === index) return;
  const arr = [...bulkPictures.value];
  const [moved] = arr.splice(draggingPicIndex.value, 1);
  arr.splice(index, 0, moved);
  bulkPictures.value = arr;
  draggingPicIndex.value = index;
  picturesTouched.value = true;
}
function onPicDragEnd() { draggingPicIndex.value = null; }
function loadPicturesFromFirst() {
  // Só preenche a referência visual — NÃO marca como editado.
  // O usuário precisa adicionar/remover/reordenar pra disparar o envio.
  const first = allItems.value[0];
  if (first && first.pictures) {
    bulkPictures.value = first.pictures.map((p) => ({
      id: p.id, preview: p.secure_url || p.url,
    }));
  }
}
function triggerFileUpload() { fileInput.value?.click(); }
async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = input.files;
  if (!files || files.length === 0) return;
  uploadingPic.value = true;
  try {
    for (const file of Array.from(files)) {
      if (!file.type.startsWith("image/")) continue;
      const resp = await uploadPicture(file);
      if (resp.status !== 201 && resp.status !== 200) continue;
      const remoteId = resp.data.id as string | undefined;
      const remoteUrl = resp.data.variations?.[0]?.secure_url as string | undefined;
      if (!remoteId && !remoteUrl) {
        console.warn("Upload retornou sem id nem URL — ignorando", resp.data);
        continue;
      }
      bulkPictures.value.push({
        id: remoteId,
        source: remoteUrl || undefined,
        preview: remoteUrl || URL.createObjectURL(file),
      });
      picturesTouched.value = true;
    }
  } catch (err) { console.error("Erro ao fazer upload:", err); }
  finally { uploadingPic.value = false; input.value = ""; }
}

// --- Attributes ---
async function loadCategoryAttributes(targetCategoryId?: string) {
  const categoryId = targetCategoryId || allItems.value[0]?.category_id;
  if (!categoryId) return;
  const refItem = allItems.value.find((i) => i.category_id === categoryId);
  if (!refItem) return;

  if (!activeAttrCategoryId.value) activeAttrCategoryId.value = categoryId;
  const isActive = activeAttrCategoryId.value === categoryId;
  if (isActive) loadingAttrs.value = true;

  try {
    const attrs = await getCategoryAttributes(categoryId);
    const values = initAttrValues(attrs, refItem.attributes || []);
    attrStateByCategory.value = {
      ...attrStateByCategory.value,
      [categoryId]: { loaded: true, categoryAttrs: attrs, attrValues: values, attrNotApplicable: new Set() },
    };
    if (isActive) {
      categoryAttrs.value = attrs;
      attrValues.value = values;
      attrNotApplicable.value = new Set();
    }
  } catch (err) { console.error("Erro ao carregar atributos:", err); }
  finally { if (isActive) loadingAttrs.value = false; }
}

function saveActiveAttrState() {
  const id = activeAttrCategoryId.value;
  if (!id) return;
  attrStateByCategory.value = {
    ...attrStateByCategory.value,
    [id]: {
      loaded: true,
      categoryAttrs: [...categoryAttrs.value],
      attrValues: { ...attrValues.value },
      attrNotApplicable: new Set(attrNotApplicable.value),
    },
  };
}

async function switchAttrCategory(newCategoryId: string) {
  if (newCategoryId === activeAttrCategoryId.value) return;
  saveActiveAttrState();
  activeAttrCategoryId.value = newCategoryId;
  const cached = attrStateByCategory.value[newCategoryId];
  if (cached?.loaded) {
    categoryAttrs.value = [...cached.categoryAttrs];
    attrValues.value = { ...cached.attrValues };
    attrNotApplicable.value = new Set(cached.attrNotApplicable);
  } else {
    categoryAttrs.value = [];
    attrValues.value = {};
    attrNotApplicable.value = new Set();
    await loadCategoryAttributes(newCategoryId);
  }
}

function onAttrInput(attrId: string, v: AttrValue) {
  attrValues.value[attrId] = v;
  if (attrValueFilled(v)) {
    const next = new Set(attrNotApplicable.value);
    next.delete(attrId);
    attrNotApplicable.value = next;
  }
}

function markAttrNotApplicable(attrId: string) {
  attrValues.value[attrId] = { value_id: null, value_name: null, number: null, unit: null };
  attrNotApplicable.value = new Set([...attrNotApplicable.value, attrId]);
}

function cancelAttrNotApplicable(attrId: string) {
  const next = new Set(attrNotApplicable.value);
  next.delete(attrId);
  attrNotApplicable.value = next;
}

function getChangedAttributesForState(
  refItem: MeliItem,
  stateCategoryAttrs: CategoryAttribute[],
  stateAttrValues: Record<string, AttrValue>,
  stateAttrNotApplicable: Set<string>,
): Record<string, unknown>[] | null {
  const currentMap: Record<string, any> = {};
  for (const attr of refItem.attributes || []) currentMap[attr.id] = attr;
  const attrTypeMap: Record<string, string> = {};
  for (const ca of stateCategoryAttrs) attrTypeMap[ca.id] = ca.value_type;

  const changed: Record<string, unknown>[] = [];
  for (const [attrId, val] of Object.entries(stateAttrValues)) {
    const orig = currentMap[attrId];
    const isNumberUnit = attrTypeMap[attrId] === "number_unit";
    const isNotApplicable = stateAttrNotApplicable.has(attrId);

    if (isNumberUnit) {
      if (isNotApplicable) {
        changed.push({ id: attrId, value_struct: null, value_name: null });
        continue;
      }
      const origStruct = orig?.value_struct;
      const origNum = typeof origStruct?.number === "number" ? origStruct.number : null;
      const origUnit = origStruct?.unit || null;
      if (val.number === origNum && val.unit === origUnit) continue;
      if (val.number === null || val.number === undefined) continue;
      changed.push({
        id: attrId,
        value_struct: { number: val.number, unit: val.unit || "" },
        value_name: val.unit ? `${val.number} ${val.unit}` : String(val.number),
      });
      continue;
    }

    if (isNotApplicable) {
      changed.push({ id: attrId, value_id: null, value_name: null });
      continue;
    }

    // value_type "number": o ML valida estritamente e um valor não-numérico
    // (ex.: texto digitado no campo) faz 400 validation_error que DERRUBA o
    // item inteiro. Parseia; se não for número, ignora só esse atributo em vez
    // de poluir o PUT. ponytail: descarta silencioso — o input já é numérico.
    if (attrTypeMap[attrId] === "number") {
      const raw = val.value_name;
      if (raw === null || raw === undefined || String(raw).trim() === "") continue;
      const n = Number(String(raw).replace(",", "."));
      if (!Number.isFinite(n)) continue;
      const clean = String(n);
      if (clean === (orig?.value_name || null)) continue;
      changed.push({ id: attrId, value_name: clean });
      continue;
    }

    if (val.value_id !== (orig?.value_id || null) || val.value_name !== (orig?.value_name || null)) {
      const entry: Record<string, unknown> = { id: attrId };
      if (val.value_id) entry.value_id = val.value_id;
      if (val.value_name) entry.value_name = val.value_name;
      changed.push(entry);
    }
  }
  return changed.length > 0 ? changed : null;
}


// Atributos de embalagem têm seção própria — não devem poluir a lista geral.
const PACKAGE_ATTR_IDS = new Set([
  "PACKAGE_LENGTH",
  "PACKAGE_WIDTH",
  "PACKAGE_HEIGHT",
  "PACKAGE_WEIGHT",
  "SHIPMENT_PACKAGING",
]);

const packageAttrs = computed(() =>
  categoryAttrs.value.filter((a) => PACKAGE_ATTR_IDS.has(a.id))
);

const nonPackageAttrs = computed(() =>
  categoryAttrs.value.filter((a) => !PACKAGE_ATTR_IDS.has(a.id))
);

// Attrs obrigatórios — sempre visíveis
const requiredAttrs = computed(() =>
  nonPackageAttrs.value.filter((a) => a.tags.required || a.tags.catalog_required)
);

const optionalAttrs = computed(() =>
  nonPackageAttrs.value.filter((a) => !a.tags.required && !a.tags.catalog_required)
);




// --- Compatibilidade (autopeças MLB-CARS_AND_VANS) ---
// Ativo automaticamente quando há veículos selecionados
const compatMode = ref<"replace" | "append">("replace");
const compatSelected = ref<VehicleCompatibility[]>([]);
const compatLoadingFirst = ref(false);
// Atributos estruturados de posição (multi-select por atributo).
// Ex: { "CAR_AXIS_POSITION": ["13701104"], "IDLER_ARM_SIDE": ["476939", "476940"] }
const compatPositionAttrs = ref<Record<string, string[]>>({});
const compatPositionDefs = ref<PositionAttribute[]>([]);
const compatPositionLoading = ref(false);

async function loadPositionAttributes() {
  compatPositionDefs.value = [];
  compatPositionAttrs.value = {}; // cada key será string[] quando preenchida
  // Pega category_id do primeiro item — assume que SKU = mesma categoria
  const firstItem = allItems.value[0];
  if (!firstItem?.category_id) return;
  compatPositionLoading.value = true;
  try {
    compatPositionDefs.value = await getPositionAttributes(firstItem.category_id);
  } catch (err) {
    console.error("Erro carregando atributos de posição:", err);
  } finally {
    compatPositionLoading.value = false;
  }
}

function vehicleLabel(v: VehicleCompatibility | ItemCompatibility): string {
  if (v.name) return v.name;
  const attrs = v.attributes || [];
  const parts: string[] = [];
  for (const id of ["BRAND", "MODEL", "VEHICLE_YEAR", "MOTOR", "TRIM"]) {
    const a = attrs.find((x) => x.id === id);
    if (a?.value_name) parts.push(a.value_name);
  }
  return parts.length ? parts.join(" · ") : (v.product_id || "—");
}

function vehiclePosition(_v: VehicleCompatibility | ItemCompatibility): string {
  // Mostra a posição estruturada (multi-select) — value_name dos selecionados
  const parts: string[] = [];
  for (const def of compatPositionDefs.value) {
    const selected = compatPositionAttrs.value[def.id] || [];
    if (selected.length > 0) {
      const names = selected
        .map((id) => def.values.find((v) => v.id === id)?.name || id)
        .join(" + ");
      parts.push(names);
    }
  }
  return parts.join(" · ");
}

const compatPositionsPayload = computed(() => {
  const out: Array<{ id: string; value_id: string; value_name?: string }> = [];
  for (const def of compatPositionDefs.value) {
    const selected = compatPositionAttrs.value[def.id] || [];
    for (const valueId of selected) {
      const valueName = def.values.find((v) => v.id === valueId)?.name;
      out.push({ id: def.id, value_id: valueId, ...(valueName ? { value_name: valueName } : {}) });
    }
  }
  return out;
});

function removeVehicle(productId: string) {
  compatSelected.value = compatSelected.value.filter((v) => v.product_id !== productId);
}

const compatSourceItemId = ref<string>("");

const compatSourceGroups = computed(() =>
  groups.value.map((g) => ({
    label: g.nickname,
    options: g.items.map((it) => ({
      value: it.id,
      label: `${it.title} (${it.id})`,
    })),
  }))
);

async function loadCompatFromSourceItem() {
  const itemId = compatSourceItemId.value;
  if (!itemId) return;
  compatLoadingFirst.value = true;
  try {
    const { compatibilities: items, positions } = await getItemCompatibilities(itemId);
    if (items.length === 0) {
      alert("Esse anúncio não tem veículos compatíveis cadastrados.");
      return;
    }
    // Substitui (não mescla) — escolher uma fonte diferente reseta a lista,
    // evitando acúmulo de veículos de fontes anteriores.
    compatSelected.value = items
      .filter((it) => it.product_id)
      .map((it) => ({
        product_id: it.product_id!,
        domain_id: it.domain_id || "MLB-CARS_AND_VANS",
        name: it.name || vehicleLabel(it),
        attributes: it.attributes || [],
        note: it.note || null,
      }));

    // Popula os dropdowns de posição com os valores lidos do item-fonte
    const newPosAttrs: Record<string, string[]> = {};
    for (const p of positions) {
      if (!newPosAttrs[p.id]) newPosAttrs[p.id] = [];
      newPosAttrs[p.id].push(p.value_id);
    }
    compatPositionAttrs.value = newPosAttrs;
  } catch (err) {
    console.error("Erro ao carregar compatibilidades do anúncio fonte:", err);
  } finally {
    compatLoadingFirst.value = false;
  }
}

// O SKU pode estar no `seller_custom_field` (legado) OU no atributo
// `SELLER_SKU`. ML moderno guarda no atributo e deixa `seller_custom_field`
// vazio — sem esse fallback o campo ficava em branco mesmo o anúncio tendo
// SKU na realidade, induzindo o usuário a achar que não havia.
function extractItemSku(item: MeliItem | undefined): string {
  if (!item) return "";
  const fromAttr = (item.attributes || []).find((a) => a.id === "SELLER_SKU");
  if (fromAttr?.value_name) return fromAttr.value_name;
  return item.seller_custom_field || "";
}

// --- Load & selection ---
async function loadGroups() {
  loading.value = true;
  touchedFields.value = new Set();
  picturesTouched.value = false;
  descriptionTouched.value = false;
  descriptionValue.value = "";
  bulkPictures.value = [];
  firstItemPkgInfo.value = null;
  packageDimsTouched.value = false;
  try {
    groups.value = await getItemsBySkuAllAccounts(sku);
    selectedIds.value = new Set(allItems.value.map((i) => i.id));
    const first = allItems.value[0];
    if (first) {
      const t = first.title || "";
      const p = first.price || 0;
      const q = first.available_quantity || 0;
      const s = extractItemSku(first);
      fields.value.title.value = t;
      fields.value.price.value = p;
      fields.value.available_quantity.value = q;
      fields.value.seller_custom_field.value = s;
      initialFields.value = {
        title: t, price: p, available_quantity: q, seller_custom_field: s,
      };
      try {
        firstItemPkgInfo.value = await getItemPackageInfo(first.id);
        const s = currentShipping.value;
        packageDims.value = {
          height: { value: s.height, unit: s.heightUnit || "cm" },
          width:  { value: s.width,  unit: s.widthUnit  || "cm" },
          length: { value: s.length, unit: s.lengthUnit || "cm" },
          weight: { value: s.weight, unit: s.weightUnit || "g"  },
        };
      } catch (e) {
        console.warn("Não foi possível carregar medidas do item:", e);
      }
      loadingDescription.value = true;
      try {
        descriptionValue.value = await getItemDescription(first.id);
      } catch (e) {
        console.warn("Não foi possível carregar descrição do item:", e);
      } finally {
        loadingDescription.value = false;
      }
    }
    await loadCategoryAttributes();
    await loadPositionAttributes();
  } catch (err) { console.error("Erro ao carregar itens:", err); }
  finally { loading.value = false; }
}

function toggleItem(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id);
  else selectedIds.value.add(id);
}

function toggleAll() {
  if (selectedIds.value.size === totalItems.value) selectedIds.value.clear();
  else selectedIds.value = new Set(allItems.value.map((i) => i.id));
}

function toggleGroup(group: AccountItemsGroup) {
  const ids = group.items.map((i) => i.id);
  const allSelected = ids.every((id) => selectedIds.value.has(id));
  const next = new Set(selectedIds.value);
  if (allSelected) ids.forEach((id) => next.delete(id));
  else ids.forEach((id) => next.add(id));
  selectedIds.value = next;
}

function groupSelectedCount(group: AccountItemsGroup): number {
  return group.items.filter((i) => selectedIds.value.has(i.id)).length;
}

function selectPremium() {
  selectedIds.value = new Set(
    allItems.value.filter((i) => i.listing_type_id === "gold_pro").map((i) => i.id)
  );
}
function selectClassico() {
  selectedIds.value = new Set(
    allItems.value.filter((i) => i.listing_type_id !== "gold_pro").map((i) => i.id)
  );
}

const premiumCount = computed(() => allItems.value.filter((i) => i.listing_type_id === "gold_pro").length);
const classicoCount = computed(() => allItems.value.filter((i) => i.listing_type_id !== "gold_pro").length);

const enabledFields = computed(() => {
  const updates: Record<string, unknown> = {};
  // Envia campos que o usuário editou (mesmo que o valor coincida com o inicial —
  // permite "normalizar" preço/estoque copiando do 1º item pros demais).
  if (touchedFields.value.has("title")) {
    updates.title = fields.value.title.value;
  }
  if (touchedFields.value.has("price")) {
    updates.price = fields.value.price.value;
  }
  if (touchedFields.value.has("available_quantity")) {
    updates.available_quantity = fields.value.available_quantity.value;
  }
  if (picturesEnabled.value && bulkPictures.value.length > 0) {
    const startIdx = keepCoverPhoto.value ? 1 : 0;
    const pics: Array<{ source: string } | { id: string }> = [];
    for (const p of bulkPictures.value.slice(startIdx)) {
      if (p.source) pics.push({ source: p.source });
      else if (p.id) pics.push({ id: p.id });
    }
    if (pics.length > 0) {
      updates.pictures = pics;
      if (keepCoverPhoto.value) updates.keep_cover_photo = true;
    }
  }

  // SELLER_SKU vai no update padrão (todos os itens); attrs de categoria vão por-categoria em applyChanges
  if (touchedFields.value.has("seller_custom_field")) {
    const sku = fields.value.seller_custom_field.value;
    updates.seller_custom_field = sku;
    updates.attributes = [{ id: "SELLER_SKU", value_name: sku }];
  }

  return updates;
});

const ML_ERROR_MESSAGES: Record<string, string> = {
  field_not_updatable: "Campo não editável neste tipo de anúncio",
  invalid_operation: "Operação não permitida",
  item_not_active: "Anúncio inativo",
  not_found: "Anúncio não encontrado",
  access_denied: "Sem permissão para editar",
  forbidden: "Sem permissão para editar",
  invalid_price: "Preço inválido",
  insufficient_stock: "Estoque insuficiente",
  item_closed: "Anúncio encerrado",
  invalid_attribute: "Atributo inválido",
  catalog_listing_not_allowed: "Anúncio de catálogo — edição restrita",
  max_images_exceeded: "Limite máximo de fotos atingido",
  "item.user_product.repeated.conflict":
    "Alteração negada pelo Mercado Livre, anúncio idêntico a outro.",
  "item.attribute.invalid":
    "Valor de atributo inválido para o Mercado Livre (ex.: texto num campo que espera número).",
};

const FIELD_LABELS: Record<string, string> = {
  title: "Título", price: "Preço", available_quantity: "Estoque",
  attributes: "Atributos", pictures: "Fotos", seller_custom_field: "SKU",
  status: "Status", description: "Descrição", listing_type_id: "Tipo de anúncio",
};

function formatError(err: unknown): string {
  if (err == null) return "Erro desconhecido";
  if (typeof err === "string") return err;
  if (typeof err !== "object") return String(err);
  const e = err as Record<string, unknown>;

  // ML API structured error
  const code = (e.error as string) || "";
  if (ML_ERROR_MESSAGES[code]) return ML_ERROR_MESSAGES[code];

  // Try to extract from cause array
  const causes = e.cause as Array<Record<string, unknown>> | undefined;
  if (causes?.length) {
    const causeMsg = causes.map((c) => {
      const causeCode = (c.code as string) || "";
      return ML_ERROR_MESSAGES[causeCode] || (c.message as string) || causeCode;
    }).join("; ");
    if (causeMsg) return causeMsg;
  }

  // HTTP status fallbacks
  const status = e.status as number | undefined;
  if (status === 403) return "Sem permissão para editar";
  if (status === 404) return "Anúncio não encontrado";
  if (status === 429) return "Muitas requisições — tente novamente em instantes";
  if (status && status >= 500) return "Erro interno do Mercado Livre";

  const msg = e.message as string | undefined;
  if (msg) return msg;

  try { return JSON.stringify(err); } catch { return String(err); }
}

function fieldLabel(field: string): string {
  return FIELD_LABELS[field] || field;
}

function itemTitle(itemId: string): string {
  const item = allItems.value.find((i) => i.id === itemId);
  return item?.title ? `"${item.title}"` : itemId;
}

function reasonDisabled(): string {
  if (saving.value) return "";
  if (selectedIds.value.size === 0) return "Selecione pelo menos um anúncio";
  if (Object.keys(enabledFields.value).length === 0 && !attrsEnabled.value && !compatReady.value && !descriptionTouched.value && !packageDimsEnabled.value) {
    return "Edite algum campo ou selecione veículos pra aplicar";
  }
  return "";
}

const compatReady = computed(() => compatSelected.value.length > 0);

const auth = useAuthStore();
const canEdit = computed(() => auth.can("bulk_edit"));
const canSave = computed(
  () =>
    canEdit.value &&
    selectedIds.value.size > 0 &&
    (Object.keys(enabledFields.value).length > 0 || attrsEnabled.value || compatReady.value || descriptionTouched.value || packageDimsEnabled.value)
);

function buildConfirmMessage(itemCount: number, accountCount: number): string {
  const lines: string[] = [];
  const fieldLabels: Record<string, string> = {
    title: "Título",
    price: "Preço",
    available_quantity: "Estoque",
    seller_custom_field: "SKU interno",
    pictures: "Fotos",
  };
  for (const key of Object.keys(enabledFields.value)) {
    if (fieldLabels[key]) lines.push(`• ${fieldLabels[key]}`);
  }
  if (attrsEnabled.value) lines.push("• Atributos da categoria");
  if (packageDimsEnabled.value) lines.push("• Medidas de embalagem");
  if (descriptionTouched.value) lines.push("• Descrição");
  if (compatReady.value) {
    const verbo = compatMode.value === "replace" ? "Substituir" : "Adicionar";
    lines.push(`• Compatibilidade: ${verbo} (${compatSelected.value.length} veículo(s))`);
  }
  return (
    `Vai aplicar a ${itemCount} anúncio(s) em ${accountCount} conta(s):\n\n` +
    lines.join("\n") +
    `\n\nConfirma?`
  );
}

async function applyChanges() {
  if (!canSave.value) return;

  // Build groups payload from selection (precisa pra montar a confirmação também)
  const map = itemAccountMap.value;
  const byAccount = new Map<number, string[]>();
  for (const id of selectedIds.value) {
    const uid = map.get(id);
    if (uid === undefined) continue;
    if (!byAccount.has(uid)) byAccount.set(uid, []);
    byAccount.get(uid)!.push(id);
  }
  const payload = Array.from(byAccount.entries()).map(([user_id, item_ids]) => ({
    user_id,
    item_ids,
  }));

  if (!confirm(buildConfirmMessage(selectedIds.value.size, payload.length))) return;

  saving.value = true;
  result.value = null;
  // Um id por "salvar" — agrupa todas as sub-operações (campos, atributos,
  // medidas, descrição, compat, posições) num único card no histórico.
  const batchId = crypto.randomUUID();
  // Mapa item_id -> título dos selecionados, pro histórico exibir SKU+título.
  const titlesMap: Record<string, string> = {};
  for (const it of allItems.value) {
    if (selectedIds.value.has(it.id) && it.title) titlesMap[it.id] = it.title;
  }
  try {
    const hasStandardFields = Object.keys(enabledFields.value).length > 0;
    const compatRun = compatReady.value;

    let combined: BulkResult | null = null;

    function dedupeErrors<T extends { item_id: string }>(errors: T[]): T[] {
      const seen = new Set<string>();
      return errors.filter((e) => { if (seen.has(e.item_id)) return false; seen.add(e.item_id); return true; });
    }
    function mergeBulkResult(a: BulkResult, b: BulkResult): BulkResult {
      const errors = dedupeErrors([...a.errors, ...b.errors]);
      const total = Math.max(a.total, b.total);
      const accounts = a.accounts
        ? a.accounts.map((acc) => {
            const match = b.accounts?.find((x) => x.user_id === acc.user_id);
            if (!match) return acc;
            const accErrors = dedupeErrors([...acc.errors, ...match.errors]);
            const accTotal = Math.max(acc.total, match.total);
            return { ...acc, total: accTotal, success: accTotal - accErrors.length, errors: accErrors };
          })
        : b.accounts;
      return { total, success: total - errors.length, errors, accounts };
    }

    if (hasStandardFields) {
      const before = buildBeforeMap(Object.keys(enabledFields.value));
      combined = await bulkUpdateMulti(payload, enabledFields.value, sku, { titles: titlesMap, batchId, before });
    }

    // Atributos por categoria — salva estado ativo, itera todas as categorias carregadas
    saveActiveAttrState();
    if (attrsEnabled.value) {
      for (const [catId, state] of Object.entries(attrStateByCategory.value)) {
        if (!state.loaded) continue;
        const refItem = allItems.value.find((i) => i.category_id === catId);
        if (!refItem) continue;
        const changedAttrs = getChangedAttributesForState(
          refItem, state.categoryAttrs, state.attrValues, state.attrNotApplicable
        );
        if (!changedAttrs) continue;

        const catSelectedIds = (itemsByCategoryId.value.get(catId) ?? [])
          .map((i) => i.id)
          .filter((id) => selectedIds.value.has(id));
        if (!catSelectedIds.length) continue;

        const catPayload = buildPayloadForItems(catSelectedIds);
        const catResult = await bulkUpdateMulti(catPayload, { attributes: changedAttrs }, sku, { titles: titlesMap, batchId });
        combined = combined ? mergeBulkResult(combined, catResult) : catResult;
      }
    }

    if (descriptionTouched.value && descriptionValue.value.trim()) {
      const descRes = await bulkUpdateDescriptionMulti(payload, descriptionValue.value.trim(), sku, batchId);
      combined = combined ? mergeBulkResult(combined, descRes) : descRes;
    }

    if (packageDimsEnabled.value) {
      const pkgAttrs: Array<{ id: string; value_struct: { number: number; unit: string }; value_name: string }> = [];
      const dimEntries: Array<[string, PackageDimField]> = [
        ["seller_package_height", packageDims.value.height],
        ["seller_package_width",  packageDims.value.width],
        ["seller_package_length", packageDims.value.length],
        ["seller_package_weight", packageDims.value.weight],
      ];
      for (const [id, dim] of dimEntries) {
        if (dim.value !== null) {
          pkgAttrs.push({
            id,
            value_struct: { number: dim.value, unit: dim.unit },
            value_name: `${dim.value} ${dim.unit}`,
          });
        }
      }
      if (pkgAttrs.length > 0) {
        const pkgRes = await bulkUpdateMulti(payload, { attributes: pkgAttrs }, sku, { titles: titlesMap, batchId });
        combined = combined ? mergeBulkResult(combined, pkgRes) : pkgRes;
      }
    }

    if (compatRun) {
      const productIds = compatSelected.value.map((v) => v.product_id);
      const vehicleNames = compatSelected.value.map((v) => v.name || v.product_id);
      const compatRes = await bulkUpdateCompatibilities(
        payload, productIds, compatMode.value, sku, vehicleNames, undefined, undefined, batchId
      );
      combined = combined ? mergeBulkResult(combined, compatRes) : compatRes;
    }

    // Posições do ML autopeças vão como "restrictions" nos veículos compatíveis
    // do user-product. Endpoint dedicado: PUT /user-products/{up_id}/compatibilities.
    // Ver docs/ml-compatibilities-api-correct.json para schema confirmado.
    if (compatPositionsPayload.value.length > 0) {
      const seen = new Set<string>();
      const positions: Array<{ attribute_id: string; value_id: string; value_name?: string }> = [];
      for (const p of compatPositionsPayload.value) {
        // Multi-select no UI vira múltiplos values com mesmo attribute_id — pegamos só o 1º
        // por enquanto (ML pode rejeitar múltiplos por restriction). Refinar depois se preciso.
        if (seen.has(p.id)) continue;
        seen.add(p.id);
        positions.push({
          attribute_id: p.id,
          value_id: p.value_id,
          ...(p.value_name ? { value_name: p.value_name } : {}),
        });
      }
      const posRes = await bulkUpdatePositions(payload, positions, sku, batchId);
      combined = combined ? mergeBulkResult(combined, posRes) : posRes;
    }

    if (combined) {
      // Eleva warnings de accounts[] para o nível raiz (onde o template exibe)
      if (!combined.warnings?.length && combined.accounts?.length) {
        const flat = combined.accounts.flatMap((a) => a.warnings ?? []);
        if (flat.length) combined = { ...combined, warnings: flat };
      }
      result.value = combined;
    }
  } catch (err) {
    console.error("Erro no bulk update:", err);
  } finally {
    saving.value = false;
  }
}

async function applyStatusChange(status: "paused" | "active") {
  if (selectedIds.value.size === 0) return;
  const label = status === "paused" ? "pausar" : "ativar";
  if (!confirm(`Vai ${label} ${selectedIds.value.size} anúncio(s). Confirma?`)) return;

  const map = itemAccountMap.value;
  const byAccount = new Map<number, string[]>();
  for (const id of selectedIds.value) {
    const uid = map.get(id);
    if (uid === undefined) continue;
    if (!byAccount.has(uid)) byAccount.set(uid, []);
    byAccount.get(uid)!.push(id);
  }
  const payload = Array.from(byAccount.entries()).map(([user_id, item_ids]) => ({
    user_id,
    item_ids,
  }));

  statusSaving.value = true;
  statusResult.value = null;
  try {
    // batchId próprio + before pro histórico mostrar "status: pausado → ativo".
    const batchId = crypto.randomUUID();
    const titlesMap: Record<string, string> = {};
    const before: Record<string, Record<string, unknown>> = {};
    for (const it of allItems.value) {
      if (!selectedIds.value.has(it.id)) continue;
      if (it.title) titlesMap[it.id] = it.title;
      before[it.id] = { status: it.status };
    }
    const res = await bulkUpdateMulti(payload, { status }, sku, { titles: titlesMap, batchId, before });
    statusResult.value = res;

    if (res.success > 0) {
      // Atualiza status localmente nos itens que tiveram sucesso
      const failedIds = new Set(res.errors.map((e) => e.item_id));
      for (const g of groups.value) {
        for (const item of g.items) {
          if (selectedIds.value.has(item.id) && !failedIds.has(item.id)) {
            item.status = status;
          }
        }
      }
    }
  } catch (err) {
    console.error(`Erro ao ${label} anúncios:`, err);
  } finally {
    statusSaving.value = false;
  }
}

function formatPrice(price: number): string {
  return price.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function accountNickname(user_id: number): string {
  return groups.value.find((g) => g.user_id === user_id)?.nickname || `Conta ${user_id}`;
}

onMounted(loadGroups);
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-center gap-3 mb-4">
      <button @click="router.push('/bulk')" class="p-2 rounded-lg hover:bg-gray-200 transition-colors">
        <ArrowLeft :size="20" />
      </button>
      <div class="flex-1">
        <h2 class="text-xl font-bold">Edição em Massa</h2>
        <p class="text-sm text-gray-500">
          SKU: {{ sku }} &middot; {{ totalItems }} anúncio(s) em
          {{ accountsWithItems.length }} conta(s)
        </p>
      </div>
      <div class="flex items-center gap-2">
        <button
          @click="applyStatusChange('paused')"
          :disabled="selectedIds.size === 0 || statusSaving || saving || !canEdit"
          :title="canEdit ? 'Pausar anúncios selecionados' : 'Você não tem permissão para edição em massa'"
          class="px-3 py-2.5 border border-amber-300 text-amber-700 bg-amber-50 rounded-xl font-medium hover:bg-amber-100 transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed text-sm"
        >
          <Loader2 v-if="statusSaving" :size="15" class="animate-spin" />
          <PauseCircle v-else :size="15" />
          Pausar
        </button>
        <button
          @click="applyStatusChange('active')"
          :disabled="selectedIds.size === 0 || statusSaving || saving || !canEdit"
          :title="canEdit ? 'Ativar (despausar) anúncios selecionados' : 'Você não tem permissão para edição em massa'"
          class="px-3 py-2.5 border border-green-300 text-green-700 bg-green-50 rounded-xl font-medium hover:bg-green-100 transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed text-sm"
        >
          <Loader2 v-if="statusSaving" :size="15" class="animate-spin" />
          <PlayCircle v-else :size="15" />
          Ativar
        </button>
        <button
          @click="applyChanges"
          :disabled="!canSave || saving"
          :title="reasonDisabled()"
          class="px-5 py-2.5 bg-meli-blue text-brand-yellow rounded-xl font-medium hover:bg-meli-blue-dark transition-colors flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Loader2 v-if="saving" :size="16" class="animate-spin" />
          {{ saving ? "Aplicando..." : "Aplicar" }}
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <template v-else-if="totalItems === 0">
      <div class="bg-white rounded-xl border shadow-sm p-8 text-center text-gray-500">
        Nenhum anúncio com esse SKU foi encontrado em nenhuma conta conectada.
      </div>
    </template>

    <template v-else>
      <!-- Result banner -->
      <div
        v-if="result"
        class="mb-4 p-3 rounded-xl border"
        :class="result.errors.length === 0 ? 'bg-green-50 border-green-200' : result.success > 0 ? 'bg-yellow-50 border-yellow-200' : 'bg-red-50 border-red-200'"
      >
        <div class="flex items-center gap-2 font-medium text-sm">
          <CheckCircle v-if="result.errors.length === 0" :size="16" class="text-green-600" />
          <XCircle v-else :size="16" class="text-red-600" />
          {{ result.success }}/{{ result.total }} atualizados com sucesso
        </div>
        <div v-if="result.accounts && result.accounts.length" class="mt-2 flex flex-wrap gap-2">
          <span
            v-for="acc in result.accounts"
            :key="acc.user_id"
            class="text-xs px-2 py-1 rounded-full bg-white border"
          >
            <strong>{{ accountNickname(acc.user_id) }}:</strong>
            {{ acc.success }}/{{ acc.total }}
          </span>
        </div>
        <div v-if="result.warnings?.length" class="mt-2 space-y-1">
          <p
            v-for="w in result.warnings"
            :key="w.item_id"
            class="text-xs text-amber-700"
          >
            ⚠ {{ itemTitle(w.item_id) }} — salvo parcialmente, campos ignorados pelo ML:
            <strong>{{ w.skipped_fields.map(fieldLabel).join(", ") }}</strong>
          </p>
        </div>
        <div v-if="result.errors.length" class="mt-2 space-y-1">
          <p v-for="err in result.errors" :key="err.item_id" class="text-xs text-red-600">
            ✕ {{ itemTitle(err.item_id) }} — {{ formatError(err.error) }}
          </p>
        </div>
      </div>

      <!-- Status change result banner -->
      <div
        v-if="statusResult"
        class="mb-4 p-3 rounded-xl border"
        :class="statusResult.errors.length === 0 ? 'bg-green-50 border-green-200' : statusResult.success > 0 ? 'bg-yellow-50 border-yellow-200' : 'bg-red-50 border-red-200'"
      >
        <div class="flex items-center gap-2 font-medium text-sm">
          <CheckCircle v-if="statusResult.errors.length === 0" :size="16" class="text-green-600" />
          <XCircle v-else :size="16" class="text-red-600" />
          Status: {{ statusResult.success }}/{{ statusResult.total }} atualizados com sucesso
        </div>
        <div v-if="statusResult.errors.length" class="mt-2 space-y-1">
          <p v-for="err in statusResult.errors" :key="err.item_id" class="text-xs text-red-600">
            ✕ {{ itemTitle(err.item_id) }} — {{ formatError(err.error) }}
          </p>
        </div>
      </div>

      <!-- Global selection bar -->
      <div class="bg-white rounded-xl border shadow-sm mb-4">
        <div class="p-3 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <button @click="toggleAll" class="p-1 rounded hover:bg-gray-100">
              <div
                class="w-5 h-5 rounded border-2 flex items-center justify-center"
                :class="selectedIds.size === totalItems ? 'bg-meli-blue border-meli-blue' : 'border-gray-300'"
              >
                <Check v-if="selectedIds.size === totalItems" :size="12" class="text-white" />
              </div>
            </button>
            <span class="text-sm text-gray-500">{{ selectedIds.size }}/{{ totalItems }}</span>
          </div>
          <div class="flex gap-2">
            <button
              v-if="premiumCount > 0"
              @click="selectPremium"
              class="px-2.5 py-1 text-xs rounded-full font-medium transition-colors"
              :class="selectedIds.size === premiumCount ? 'bg-amber-200 text-amber-800' : 'bg-amber-50 text-amber-600 hover:bg-amber-100'"
            >Premium ({{ premiumCount }})</button>
            <button
              v-if="classicoCount > 0"
              @click="selectClassico"
              class="px-2.5 py-1 text-xs rounded-full font-medium transition-colors"
              :class="selectedIds.size === classicoCount ? 'bg-gray-300 text-gray-800' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'"
            >Clássico ({{ classicoCount }})</button>
          </div>
        </div>

        <!-- Groups by account -->
        <div class="max-h-96 overflow-y-auto border-t">
          <div
            v-for="group in groups"
            :key="group.user_id"
            class="border-b last:border-b-0"
          >
            <button
              @click="toggleGroup(group)"
              class="w-full flex items-center gap-2 px-3 py-2 bg-gray-50 hover:bg-gray-100 text-left sticky top-0"
            >
              <div
                class="w-4 h-4 rounded border-2 flex items-center justify-center flex-shrink-0"
                :class="groupSelectedCount(group) === group.items.length && group.items.length > 0 ? 'bg-meli-blue border-meli-blue' : 'border-gray-300'"
              >
                <Check v-if="groupSelectedCount(group) === group.items.length && group.items.length > 0" :size="10" class="text-white" />
              </div>
              <Users :size="14" class="text-meli-blue" />
              <span class="text-sm font-semibold text-gray-700">{{ group.nickname }}</span>
              <span class="text-xs text-gray-500">
                {{ groupSelectedCount(group) }}/{{ group.items.length }}
              </span>
              <span
                v-if="group.error"
                class="ml-auto text-[10px] text-red-500 truncate max-w-[200px]"
                :title="group.error"
              >erro</span>
            </button>
            <div v-if="group.items.length" class="divide-y">
              <div
                v-for="item in group.items"
                :key="item.id"
                class="flex items-center gap-3 px-3 py-2 pl-8 hover:bg-gray-50 cursor-pointer"
                @click="toggleItem(item.id)"
              >
                <div
                  class="w-4 h-4 rounded border-2 flex items-center justify-center flex-shrink-0"
                  :class="selectedIds.has(item.id) ? 'bg-meli-blue border-meli-blue' : 'border-gray-300'"
                >
                  <Check v-if="selectedIds.has(item.id)" :size="10" class="text-white" />
                </div>
                <img :src="item.thumbnail" class="w-8 h-8 rounded object-cover bg-gray-100 flex-shrink-0" />
                <p class="text-sm truncate flex-1 min-w-0">{{ item.title }}</p>
                <span class="text-xs font-medium flex-shrink-0">{{ formatPrice(item.price) }}</span>
                <div class="flex items-center gap-1 flex-shrink-0">
                  <ShippingBadge :logistic-type="(item.shipping?.logistic_type as string) ?? null" />
                  <ListingTypeBadge :type="item.listing_type_id" />
                  <StatusBadge :status="item.status" />
                </div>
              </div>
            </div>
            <div v-else class="px-3 py-2 pl-8 text-xs text-gray-400 italic">
              Nenhum anúncio nessa conta
            </div>
          </div>
        </div>
      </div>

      <!-- Tabs -->
      <div class="flex gap-1 mb-4 bg-white rounded-xl border shadow-sm p-1">
        <button
          @click="activeTab = 'basic'"
          class="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          :class="activeTab === 'basic' ? 'bg-meli-blue text-brand-yellow' : 'text-gray-600 hover:bg-gray-100'"
        >
          <Package :size="15" /> Campos
        </button>
        <button
          @click="activeTab = 'attributes'"
          class="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          :class="activeTab === 'attributes' ? 'bg-meli-blue text-brand-yellow' : 'text-gray-600 hover:bg-gray-100'"
        >
          <Settings :size="15" /> Atributos
          <span v-if="loadingAttrs" class="w-3 h-3 border-2 border-current border-t-transparent rounded-full animate-spin" />
        </button>
        <button
          @click="activeTab = 'pictures'"
          class="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          :class="activeTab === 'pictures' ? 'bg-meli-blue text-brand-yellow' : 'text-gray-600 hover:bg-gray-100'"
        >
          <Image :size="15" /> Fotos
        </button>
        <button
          @click="activeTab = 'compatibility'"
          class="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          :class="activeTab === 'compatibility' ? 'bg-meli-blue text-brand-yellow' : 'text-gray-600 hover:bg-gray-100'"
        >
          <Car :size="15" /> Compatibilidade
          <span
            v-if="compatReady"
            class="text-[10px] bg-green-100 text-green-700 px-1.5 py-0.5 rounded-full"
          >{{ compatSelected.length }}</span>
        </button>
      </div>

      <!-- Tab: Basic -->
      <div v-if="activeTab === 'basic'" class="bg-white rounded-xl border shadow-sm p-5 space-y-4">
        <p class="text-xs text-gray-500 -mb-2">
          Valores carregados do <strong>1º anúncio</strong> como referência. Só os campos que você editar são enviados pros itens selecionados.
        </p>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Título</label>
          <input
            v-model="fields.title.value"
            @input="markTouched('title')"
            type="text"
            maxlength="60"
            class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
            :class="touchedFields.has('title') ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
          />
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Preço (R$)</label>
            <input
              v-model.number="fields.price.value"
              @input="markTouched('price')"
              type="number"
              step="0.01"
              min="0"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              :class="touchedFields.has('price') ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Estoque</label>
            <input
              v-model.number="fields.available_quantity.value"
              @input="markTouched('available_quantity')"
              type="number"
              min="0"
              class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              :class="touchedFields.has('available_quantity') ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
            />
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">SKU interno</label>
          <input
            v-model="fields.seller_custom_field.value"
            @input="markTouched('seller_custom_field')"
            type="text"
            class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
            :class="touchedFields.has('seller_custom_field') ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
          />
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">
            Descrição
            <span class="text-xs font-normal text-gray-400 ml-1">— aplicada como texto simples a todos os selecionados</span>
          </label>
          <div v-if="loadingDescription" class="flex items-center gap-2 text-xs text-gray-400 py-2">
            <Loader2 :size="13" class="animate-spin" /> Carregando descrição...
          </div>
          <textarea
            v-else
            v-model="descriptionValue"
            @input="descriptionTouched = true"
            rows="6"
            placeholder="Descrição do anúncio..."
            class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue resize-y"
            :class="descriptionTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
          />
        </div>

        <div>
          <label class="flex items-center gap-2 text-sm font-medium text-gray-700 mb-1">
            <Ruler :size="14" /> Medidas de embalagem
            <span v-if="packageDimsTouched" class="text-xs text-meli-blue font-normal">— editado</span>
          </label>
          <div class="mt-2 grid grid-cols-2 sm:grid-cols-4 gap-2">
            <!-- Altura -->
            <div>
              <label class="block text-xs text-gray-500 mb-1">Altura</label>
              <div class="flex gap-1">
                <input
                  v-model.number="packageDims.height.value"
                  @input="packageDimsTouched = true"
                  type="number"
                  min="0"
                  step="0.1"
                  placeholder="—"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  :class="packageDimsTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
                />
                <select
                  v-model="packageDims.height.unit"
                  @change="packageDimsTouched = true"
                  class="px-2 py-2 border border-gray-200 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
                >
                  <option value="cm">cm</option>
                </select>
              </div>
            </div>
            <!-- Largura -->
            <div>
              <label class="block text-xs text-gray-500 mb-1">Largura</label>
              <div class="flex gap-1">
                <input
                  v-model.number="packageDims.width.value"
                  @input="packageDimsTouched = true"
                  type="number"
                  min="0"
                  step="0.1"
                  placeholder="—"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  :class="packageDimsTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
                />
                <select
                  v-model="packageDims.width.unit"
                  @change="packageDimsTouched = true"
                  class="px-2 py-2 border border-gray-200 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
                >
                  <option value="cm">cm</option>
                </select>
              </div>
            </div>
            <!-- Comprimento -->
            <div>
              <label class="block text-xs text-gray-500 mb-1">Comprimento</label>
              <div class="flex gap-1">
                <input
                  v-model.number="packageDims.length.value"
                  @input="packageDimsTouched = true"
                  type="number"
                  min="0"
                  step="0.1"
                  placeholder="—"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  :class="packageDimsTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
                />
                <select
                  v-model="packageDims.length.unit"
                  @change="packageDimsTouched = true"
                  class="px-2 py-2 border border-gray-200 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
                >
                  <option value="cm">cm</option>
                </select>
              </div>
            </div>
            <!-- Peso -->
            <div>
              <label class="block text-xs text-gray-500 mb-1">Peso</label>
              <div class="flex gap-1">
                <input
                  v-model.number="packageDims.weight.value"
                  @input="packageDimsTouched = true"
                  type="number"
                  min="0"
                  step="1"
                  placeholder="—"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  :class="packageDimsTouched ? 'border-meli-blue ring-1 ring-meli-blue/30' : 'border-gray-200'"
                />
                <select
                  v-model="packageDims.weight.unit"
                  @change="packageDimsTouched = true"
                  class="px-2 py-2 border border-gray-200 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
                >
                  <option value="g">g</option>
                  <option value="kg">kg</option>
                </select>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab: Attributes -->
      <div v-if="activeTab === 'attributes'" class="bg-white rounded-xl border shadow-sm divide-y">

        <!-- Seletor de categoria (só aparece quando o SKU tem itens de categorias diferentes) -->
        <div v-if="hasMultipleCategories" class="p-4 flex flex-wrap gap-2 items-center">
          <span class="text-xs font-semibold text-gray-500 uppercase tracking-wide mr-1">Categoria:</span>
          <button
            v-for="cat in distinctCategories"
            :key="cat.categoryId"
            type="button"
            @click="switchAttrCategory(cat.categoryId)"
            class="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium border transition-colors"
            :class="activeAttrCategoryId === cat.categoryId
              ? 'bg-meli-blue text-brand-yellow border-meli-blue'
              : 'bg-white text-gray-600 border-gray-300 hover:border-meli-blue hover:text-meli-blue'"
          >
            {{ cat.categoryId }}
            <span class="text-[10px] opacity-75">({{ (itemsByCategoryId.get(cat.categoryId) ?? []).filter(i => selectedIds.has(i.id)).length }}/{{ cat.itemCount }} sel.)</span>
          </button>
          <span class="ml-auto text-xs text-gray-400">
            Atributos se aplicam apenas aos itens da categoria selecionada
          </span>
        </div>

        <div v-if="loadingAttrs" class="flex items-center justify-center py-10">
          <Loader2 :size="24" class="animate-spin text-meli-blue" />
        </div>

        <template v-else>
          <!-- Medidas da embalagem (PACKAGE_*) -->
          <div v-if="packageAttrs.length" class="p-5">
            <div class="flex items-center gap-2 mb-3">
              <Ruler :size="14" class="text-gray-500" />
              <span class="text-xs font-semibold text-gray-600 uppercase tracking-wide">Medidas da embalagem</span>
              <span class="text-[10px] text-gray-400 font-normal ml-1">via atributos da categoria</span>
            </div>
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div v-for="attr in packageAttrs" :key="attr.id">
                <label class="block text-xs font-medium text-gray-600 mb-1" :title="attr.tooltip || undefined">
                  {{ attr.name }}
                  <span v-if="attr.tags.required || attr.tags.catalog_required" class="text-red-400 ml-0.5">*</span>
                </label>
                <AttributeInput
                  :attr="attr"
                  :model-value="attrValues[attr.id]"
                  size="sm"
                  @update:model-value="(v) => onAttrInput(attr.id, v)"
                />
              </div>
            </div>
          </div>

          <!-- Atributos obrigatórios -->
          <div v-if="requiredAttrs.length" class="p-5">
            <div class="flex items-center gap-2 mb-3">
              <span class="text-xs font-semibold text-red-600 uppercase tracking-wide">Obrigatórios</span>
              <span class="text-[10px] bg-red-50 text-red-500 border border-red-200 px-1.5 py-0.5 rounded-full font-medium">{{ requiredAttrs.length }}</span>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div v-for="attr in requiredAttrs" :key="attr.id">
                <label class="block text-xs font-medium text-gray-700 mb-1" :title="attr.tooltip || undefined">
                  {{ attr.name }} <span class="text-red-400">*</span>
                </label>
                <AttributeInput
                  :attr="attr"
                  :model-value="attrValues[attr.id]"
                  @update:model-value="(v) => onAttrInput(attr.id, v)"
                />
              </div>
            </div>
          </div>

          <!-- Opcionais -->
          <div v-if="optionalAttrs.length" class="p-5">
            <div class="flex items-center gap-2 mb-3">
              <span class="text-xs font-semibold text-gray-500 uppercase tracking-wide">Opcionais</span>
              <span class="text-[10px] bg-gray-100 text-gray-500 border border-gray-200 px-1.5 py-0.5 rounded-full font-medium">{{ optionalAttrs.length }}</span>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div v-for="attr in optionalAttrs" :key="attr.id">
                <label class="block text-xs font-medium text-gray-700 mb-1" :title="attr.tooltip || undefined">
                  {{ attr.name }}
                </label>
                <div :class="{'opacity-40 pointer-events-none': attrNotApplicable.has(attr.id)}">
                  <AttributeInput
                    :attr="attr"
                    :model-value="attrValues[attr.id]"
                    @update:model-value="(v) => onAttrInput(attr.id, v)"
                  />
                </div>
                <button
                  v-if="attrNotApplicable.has(attr.id)"
                  @click="cancelAttrNotApplicable(attr.id)"
                  type="button"
                  class="mt-1 flex items-center gap-1 text-xs px-2 py-0.5 bg-gray-200 text-gray-700 rounded-full hover:bg-gray-300 transition-colors"
                ><X :size="10" /> Não se aplica</button>
                <button
                  v-else
                  @click="markAttrNotApplicable(attr.id)"
                  type="button"
                  class="mt-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
                >Não se aplica</button>
              </div>
            </div>
          </div>

          <div v-if="!packageAttrs.length && !requiredAttrs.length && !optionalAttrs.length" class="p-8 text-center text-sm text-gray-400">
            Nenhum atributo editável encontrado para esta categoria.
          </div>
        </template>
      </div>

      <!-- Tab: Pictures -->
      <div v-if="activeTab === 'pictures'" class="bg-white rounded-xl border shadow-sm p-5">
        <h4 class="text-sm font-semibold text-gray-800 mb-1">Fotos</h4>
        <p class="text-xs text-gray-500 mb-3">
          Adicione ou cole fotos abaixo — só são enviadas se houver alguma na lista.
        </p>
        <label class="flex items-center gap-2 mb-4 cursor-pointer select-none w-fit">
          <div
            @click="keepCoverPhoto = !keepCoverPhoto"
            class="w-4 h-4 rounded border-2 flex items-center justify-center flex-shrink-0 transition-colors"
            :class="keepCoverPhoto ? 'bg-meli-blue border-meli-blue' : 'border-gray-300 hover:border-meli-blue'"
          >
            <Check v-if="keepCoverPhoto" :size="10" class="text-white" />
          </div>
          <span class="text-sm text-gray-700">Não alterar foto de capa</span>
        </label>

        <div class="space-y-3">
          <button v-if="bulkPictures.length === 0 && totalItems > 0" @click="loadPicturesFromFirst"
            class="w-full px-3 py-2 border border-dashed rounded-lg text-sm text-gray-500 hover:bg-gray-50 hover:text-gray-700 transition-colors"
          >Carregar fotos do primeiro anúncio</button>

          <!-- Slot bloqueado da capa quando keepCoverPhoto está ativo -->
          <div v-if="keepCoverPhoto"
            class="flex items-center gap-2 p-2 border rounded-lg bg-gray-100 opacity-60 select-none"
          >
            <Lock :size="14" class="text-gray-400 flex-shrink-0" />
            <div class="w-10 h-10 rounded bg-gray-200 flex items-center justify-center flex-shrink-0">
              <Image :size="14" class="text-gray-400" />
            </div>
            <span class="text-xs text-gray-500 flex-1">Foto 1 — Principal (mantida do anúncio)</span>
          </div>

          <div v-for="(pic, index) in bulkPictures" :key="index"
            class="flex items-center gap-2 p-2 border rounded-lg bg-gray-50"
            draggable="true" @dragstart="onPicDragStart(index)" @dragover="(e) => onPicDragOver(e, index)" @dragend="onPicDragEnd"
          >
            <GripVertical :size="14" class="text-gray-400 cursor-grab flex-shrink-0" />
            <img :src="pic.preview || pic.source" class="w-10 h-10 rounded object-cover bg-white flex-shrink-0" />
            <span class="text-xs text-gray-500 truncate flex-1">
              {{ keepCoverPhoto ? `Foto ${index + 2}` : (index === 0 ? 'Principal' : `Foto ${index + 1}`) }}
            </span>
            <button @click="removePic(index)" class="p-1 hover:bg-red-50 rounded flex-shrink-0">
              <Trash2 :size="14" class="text-red-500" />
            </button>
          </div>

          <input ref="fileInput" type="file" multiple class="hidden" @change="onFileSelected" />
          <button @click="triggerFileUpload" :disabled="uploadingPic"
            class="w-full px-3 py-2 border-2 border-dashed border-meli-blue rounded-lg text-sm text-meli-blue hover:bg-blue-50 transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
          >
            <Loader2 v-if="uploadingPic" :size="14" class="animate-spin" />
            <Plus v-else :size="14" />
            {{ uploadingPic ? "Enviando..." : "Upload do PC" }}
          </button>

          <div class="flex gap-2">
            <input v-model="newPicUrl" type="text" placeholder="Ou cole a URL da imagem..."
              class="flex-1 px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              @keyup.enter="addPicByUrl" />
            <button @click="addPicByUrl"
              class="px-3 py-2 bg-meli-blue text-brand-yellow rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1"
            ><Plus :size="14" /></button>
          </div>

          <p v-if="bulkPictures.length > 0" class="text-xs text-amber-600">
            <template v-if="keepCoverPhoto">
              A foto de capa de cada anúncio será mantida — as demais fotos serão substituídas pelas {{ bulkPictures.length > 1 ? bulkPictures.length - 1 : 0 }} foto(s) adicionadas (da 2ª em diante).
            </template>
            <template v-else>
              Estas fotos vão substituir todas as fotos atuais dos {{ selectedIds.size }} anúncio(s) selecionados.
            </template>
          </p>
        </div>
      </div>

      <!-- Tab: Compatibilidade (autopeças) -->
      <div v-if="activeTab === 'compatibility'" class="bg-white rounded-xl border shadow-sm p-5 space-y-4">
        <div class="flex items-center justify-between">
          <h4 class="flex items-center gap-2 text-sm font-semibold text-gray-800">
            <Car :size="16" />
            Compatibilidade (veículos)
          </h4>
          <span class="text-xs text-gray-400">só envia se houver veículos selecionados abaixo</span>
        </div>

        <div class="space-y-4">
          <!-- Mode -->
          <div>
            <label class="block text-xs text-gray-500 mb-1">Modo</label>
            <div class="flex gap-2">
              <label
                class="flex-1 flex items-center gap-2 px-3 py-2 border rounded-lg cursor-pointer text-sm transition-colors"
                :class="compatMode === 'replace' ? 'border-meli-blue bg-blue-50 font-medium' : 'border-gray-200'"
              >
                <input
                  type="radio"
                  name="compat-mode"
                  value="replace"
                  v-model="compatMode"
                  class="rounded"
                />
                <span>Substituir lista atual</span>
              </label>
              <label
                class="flex-1 flex items-center gap-2 px-3 py-2 border rounded-lg cursor-pointer text-sm transition-colors"
                :class="compatMode === 'append' ? 'border-meli-blue bg-blue-50 font-medium' : 'border-gray-200'"
              >
                <input
                  type="radio"
                  name="compat-mode"
                  value="append"
                  v-model="compatMode"
                  class="rounded"
                />
                <span>Adicionar aos existentes</span>
              </label>
            </div>
          </div>

          <!-- Escolher anúncio fonte -->
          <div class="space-y-2">
            <label class="block text-xs text-gray-500">
              Copiar compatibilidades de um anúncio deste SKU
            </label>
            <div class="flex gap-2">
              <div class="flex-1">
                <SelectMenu
                  v-model="compatSourceItemId"
                  :groups="compatSourceGroups"
                  placeholder="— Escolha o anúncio fonte —"
                  empty-label="— Escolha o anúncio fonte —"
                />
              </div>
              <button
                @click="loadCompatFromSourceItem"
                :disabled="compatLoadingFirst || !compatSourceItemId"
                class="px-4 py-2 bg-meli-blue text-brand-yellow rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1 disabled:opacity-50"
              >
                <Loader2 v-if="compatLoadingFirst" :size="14" class="animate-spin" />
                <Plus v-else :size="14" />
                Carregar
              </button>
            </div>
            <p class="text-[11px] text-gray-400">
              O anúncio escolhido precisa ter compatibilidade cadastrada no painel do ML. Funciona só com anúncios das contas conectadas.
            </p>
          </div>

          <!-- Posição da peça (dropdowns estruturados por categoria) -->
          <div class="border-t pt-4 space-y-3">
            <div>
              <h4 class="text-sm font-medium text-gray-700">
                Posição da peça
                <span class="text-gray-400 font-normal">(opcional)</span>
              </h4>
              <p class="text-[11px] text-gray-400 mt-0.5">
                Atributos estruturados de posição da categoria, aplicados a
                <strong>todos</strong> os veículos selecionados.
                Importante pro ranking — anúncios sem posição podem cair no resultado.
              </p>
            </div>

            <div v-if="compatPositionLoading" class="text-xs text-gray-400">
              Carregando opções da categoria...
            </div>

            <div
              v-else-if="compatPositionDefs.length === 0"
              class="text-xs text-gray-400 italic"
            >
              Essa categoria não tem atributos de posição estruturada.
            </div>

            <div
              v-else
              class="grid grid-cols-1 sm:grid-cols-2 gap-3"
            >
              <div v-for="def in compatPositionDefs" :key="def.id">
                <label class="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">
                  {{ def.name }}
                  <span v-if="(compatPositionAttrs[def.id] || []).length > 1" class="text-[10px] text-meli-blue font-normal ml-1">
                    {{ (compatPositionAttrs[def.id] || []).length }} selecionados
                  </span>
                </label>
                <PositionSelect
                  :model-value="compatPositionAttrs[def.id] || []"
                  @update:model-value="compatPositionAttrs[def.id] = $event"
                  :options="def.values"
                />
              </div>
            </div>

          </div>

          <!-- Selected list -->
          <div>
            <div class="flex items-center justify-between mb-2">
              <span class="text-xs font-semibold text-gray-600">
                Veículos selecionados ({{ compatSelected.length }})
              </span>
              <button
                v-if="compatSelected.length > 0"
                @click="compatSelected = []"
                class="text-xs text-red-500 hover:underline"
              >
                Limpar tudo
              </button>
            </div>
            <div v-if="compatSelected.length === 0" class="text-xs text-gray-400 text-center py-6 border border-dashed rounded-lg">
              Nenhum veículo selecionado. Use a busca acima ou cole uma lista.
            </div>
            <div v-else class="space-y-1 max-h-60 overflow-y-auto">
              <div
                v-for="v in compatSelected"
                :key="v.product_id"
                class="flex items-center gap-2 px-3 py-2 bg-gray-50 rounded-lg"
              >
                <Car :size="14" class="text-meli-blue flex-shrink-0" />
                <div class="flex-1 min-w-0">
                  <div class="text-sm truncate flex items-center gap-2">
                    <span class="truncate">{{ vehicleLabel(v) }}</span>
                    <span
                      v-if="vehiclePosition(v)"
                      class="text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 flex-shrink-0"
                      title="Posição da peça"
                    >
                      {{ vehiclePosition(v) }}
                    </span>
                  </div>
                  <div class="text-[10px] text-gray-400 font-mono truncate">{{ v.product_id }}</div>
                </div>
                <button
                  @click="removeVehicle(v.product_id)"
                  class="p-1 hover:bg-red-50 rounded flex-shrink-0"
                  title="Remover"
                >
                  <Trash2 :size="14" class="text-red-500" />
                </button>
              </div>
            </div>
          </div>

          <p v-if="compatReady" class="text-xs text-amber-600">
            {{ compatMode === "replace"
              ? `A lista atual de ${selectedIds.size} anúncio(s) será substituída por esses ${compatSelected.length} veículo(s).`
              : `Esses ${compatSelected.length} veículo(s) serão adicionados aos ${selectedIds.size} anúncio(s) (sem remover os existentes).` }}
          </p>
        </div>
      </div>

    </template>
  </div>
</template>

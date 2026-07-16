<script setup lang="ts">
import { ref, computed, watch, onMounted } from "vue";
import {
  getClonePreview,
  createCloneMulti,
  type ClonePreview,
  type CloneMultiResult,
  type MissingAttrDef,
} from "@/api/clone";
import { uploadPicture, getCategoryAttributes, type CategoryAttribute } from "@/api/items";
import { useAuthStore } from "@/stores/auth";
import {
  Search,
  Loader2,
  Copy,
  ExternalLink,
  CheckCircle,
  AlertTriangle,
  Image,
  Users,
  XCircle,
  Tag,
  Plus,
  Trash2,
  Car,
  GripVertical,
  Package,
} from "lucide-vue-next";

interface EditableAttr {
  id: string;
  name: string;
  value_name: string;
  value_id?: string;
  value_struct?: { number: number; unit: string };
  fromOriginal: boolean;
  // Obrigatório na categoria (tags.required/catalog_required) — separa "obrigatórios"
  // dos "demais" na tela, igual ao BulkEdit.
  required?: boolean;
  // Enriquecimento via /categories/{id}/attributes — espelha a estrutura do BulkEdit
  value_type?: string; // "string" | "number" | "number_unit" | "list" | "boolean"
  values?: Array<{ id: string; name: string }>;
  allowed_units?: Array<{ id: string; name: string }>;
  default_unit?: string | null;
  allow_custom_value?: boolean;
  numberValue?: number | null;
  unitValue?: string | null;
}

const auth = useAuthStore();

interface CloneMultiResultWithType extends CloneMultiResult {
  results: Array<
    CloneMultiResult["results"][number] & {
      listing_type?: "premium" | "classico";
      title_variant?: string;
    }
  >;
}

const MAX_TITLE_VARIANTS = 20;

const inputUrl = ref("");
const preview = ref<ClonePreview | null>(null);
const loading = ref(false);
const creating = ref(false);
const result = ref<CloneMultiResultWithType | { error: string } | null>(null);
const dimensionsMsg = ref<string | null>(null);

const selectedAccounts = ref<Set<number>>(new Set());
const editableAttrs = ref<EditableAttr[]>([]);

const PACKAGE_ATTR_IDS = new Set([
  "seller_package_height", "seller_package_width",
  "seller_package_length", "seller_package_weight",
  "PACKAGE_HEIGHT", "PACKAGE_WIDTH", "PACKAGE_LENGTH", "PACKAGE_WEIGHT",
  // Forma MAIÚSCULA que vem do /user-products — sem isso as medidas caíam
  // no meio dos atributos normais em vez do card "Medidas da embalagem".
  "SELLER_PACKAGE_HEIGHT", "SELLER_PACKAGE_WIDTH",
  "SELLER_PACKAGE_LENGTH", "SELLER_PACKAGE_WEIGHT",
]);

function canonicalPackageAttrId(id: string): string | null {
  switch (id.toUpperCase()) {
    case "PACKAGE_HEIGHT":
    case "SELLER_PACKAGE_HEIGHT":
      return "seller_package_height";
    case "PACKAGE_WIDTH":
    case "SELLER_PACKAGE_WIDTH":
      return "seller_package_width";
    case "PACKAGE_LENGTH":
    case "SELLER_PACKAGE_LENGTH":
      return "seller_package_length";
    case "PACKAGE_WEIGHT":
    case "SELLER_PACKAGE_WEIGHT":
      return "seller_package_weight";
    default:
      return null;
  }
}

function packageAttrName(id: string): string {
  switch (id) {
    case "seller_package_height": return "Altura da embalagem";
    case "seller_package_width": return "Largura da embalagem";
    case "seller_package_length": return "Comprimento da embalagem";
    case "seller_package_weight": return "Peso da embalagem";
    default: return id;
  }
}

const packageAttrs = computed(() => editableAttrs.value.filter((a) => PACKAGE_ATTR_IDS.has(a.id)));
const regularAttrs = computed(() => editableAttrs.value.filter((a) => !PACKAGE_ATTR_IDS.has(a.id)));
// Separa os atributos (fora medidas) em obrigatórios x demais, pela tag da categoria.
const requiredAttrs = computed(() => regularAttrs.value.filter((a) => a.required));
const optionalAttrs = computed(() => regularAttrs.value.filter((a) => !a.required));
// Grupos renderizados na tela (obrigatórios primeiro); some o grupo vazio.
const attrGroups = computed(() =>
  [
    { key: "required", label: "Obrigatórios", items: requiredAttrs.value },
    { key: "optional", label: "Demais atributos", items: optionalAttrs.value },
  ].filter((g) => g.items.length > 0)
);

function removeAttributeById(id: string) {
  const idx = editableAttrs.value.findIndex((a) => a.id === id);
  if (idx !== -1) editableAttrs.value.splice(idx, 1);
}

// "Não se aplica" — marca o atributo pra não ir no payload de criação.
// Mesma UX do BulkEdit. O attr fica visível mas opaco + chip cinza.
const attrNotApplicable = ref<Set<string>>(new Set());

function markAttrNotApplicable(id: string) {
  attrNotApplicable.value = new Set([...attrNotApplicable.value, id]);
}

function cancelAttrNotApplicable(id: string) {
  const next = new Set(attrNotApplicable.value);
  next.delete(id);
  attrNotApplicable.value = next;
}

// Modal de atributos obrigatórios ausentes (medidas de embalagem etc.)
const showMissingModal = ref(false);

interface ModalInput {
  id: string;
  name: string;
  value_type: string;
  numValue: string;
  unit: string;
  strValue: string;
  allowed_units: Array<{ id: string; name: string }>;
  values: Array<{ id: string; name: string }>;
  hint: string;
}
const modalInputs = ref<ModalInput[]>([]);

function isIntegerUnit(unit: string): boolean {
  return unit === "cm" || unit === "mm" || unit === "m";
}

function numFieldError(inp: ModalInput): string | null {
  if (inp.value_type !== "number_unit") return null;
  if (inp.numValue === "") return null;
  const raw = inp.numValue.replace(",", ".");
  const num = parseFloat(raw);
  if (isNaN(num)) return "Digite um número válido (ex: 20 ou 1,5).";
  if (num <= 0) return "O valor deve ser maior que zero.";
  if (!inp.unit) return "Selecione a unidade.";
  return null;
}

function extractErrorMessage(err: unknown): string {
  if (!err) return "Erro desconhecido";
  const e = err as { response?: { data?: { detail?: unknown; message?: string; error?: string } }; message?: string };
  const detail = e.response?.data?.detail;
  if (detail) {
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      const first = detail[0] as Record<string, unknown> | undefined;
      return first ? `Validação: ${first.msg} (${(first.loc as string[])?.join(".")})` : JSON.stringify(detail);
    }
    if (typeof detail === "object") return JSON.stringify(detail);
  }
  const msg = e.response?.data?.message || e.response?.data?.error;
  if (msg) return msg;
  return (err instanceof Error ? err.message : String(err)) || "Erro desconhecido";
}

const missingAttrsValid = computed(() =>
  modalInputs.value.every((inp) => {
    if (inp.value_type === "number_unit") {
      const raw = inp.numValue.replace(",", ".");
      const num = parseFloat(raw);
      if (isNaN(num) || num <= 0) return false;
      return inp.unit !== "";
    }
    return inp.strValue.trim() !== "";
  })
);

// Atributos de medidas de embalagem — usados para o modal quando ML rejeita por falta deles.
// Usa os IDs seller_package_* (formato exigido pelo ML ao criar item).
const PACKAGE_DIMENSION_ATTRS: MissingAttrDef[] = [
  { id: "seller_package_height", name: "Altura da embalagem", value_type: "number_unit", default_unit: "cm", allowed_units: [{ id: "cm", name: "cm" }] },
  { id: "seller_package_width",  name: "Largura da embalagem", value_type: "number_unit", default_unit: "cm", allowed_units: [{ id: "cm", name: "cm" }] },
  { id: "seller_package_length", name: "Comprimento da embalagem", value_type: "number_unit", default_unit: "cm", allowed_units: [{ id: "cm", name: "cm" }] },
  { id: "seller_package_weight", name: "Peso da embalagem", value_type: "number_unit", default_unit: "g",  allowed_units: [{ id: "g", name: "g" }, { id: "kg", name: "kg" }] },
];

function isPackageDimsError(msg: string): boolean {
  const lower = msg.toLowerCase();
  return (
    lower.includes("seller_package") ||
    lower.includes("package.dimensions") ||
    lower.includes("medidas da embalagem")
  );
}

// Editable clone fields
const form = ref({
  price: 0,
  available_quantity: 1,
  seller_custom_field: "",
  description: "",
  createPremium: true,
  createClassico: false,
  localPickUp: false,
  warrantyType: "Garantia do vendedor", // ou "Garantia de fábrica" / "Sem garantia"
  warrantyMonths: 3,
});

// Variantes de título — a primeira sempre existe. Clique em "+" adiciona mais.
const titles = ref<string[]>([""]);
const validTitleCount = computed(
  () => titles.value.filter((t) => t.trim().length > 0).length
);

function addTitle() {
  if (titles.value.length >= MAX_TITLE_VARIANTS) return;
  titles.value.push(titles.value[0] ?? "");
}

function isDuplicateTitle(index: number): boolean {
  const val = titles.value[index].trim();
  if (!val) return false;
  return titles.value.some((t, i) => i !== index && t.trim() === val);
}

const hasDuplicateTitles = computed(() =>
  titles.value.some((_, i) => isDuplicateTitle(i))
);

function removeTitle(index: number) {
  if (index === 0 || titles.value.length <= 1) return;
  titles.value.splice(index, 1);
}

const CLASSICO_DISCOUNT = 0.05;
const classicoPrice = computed(() =>
  Math.round(form.value.price * (1 - CLASSICO_DISCOUNT) * 100) / 100
);

async function fetchPreview() {
  if (!inputUrl.value.trim()) return;
  loading.value = true;
  preview.value = null;
  result.value = null;
  try {
    preview.value = await getClonePreview(inputUrl.value.trim());
    const s = preview.value.suggested;
    const origByName: Record<string, string> = {};
    for (const a of preview.value.original.attributes) {
      if (a.id && a.name) origByName[a.id] = a.name;
    }

    // Busca as definições da categoria pra saber value_type, allowed_units, etc.
    // Igual ao BulkEdit — permite editar number_unit com seletor de unidade.
    let catDefs: CategoryAttribute[] = [];
    if (s.category_id) {
      try {
        catDefs = await getCategoryAttributes(s.category_id);
      } catch (e) {
        console.warn("Não foi possível carregar atributos da categoria:", e);
      }
    }
    const defById: Record<string, CategoryAttribute> = {};
    for (const d of catDefs) defById[d.id] = d;

    // Para number_unit, tenta extrair number + unit do value_struct ou parsear do value_name.
    function parseNumUnit(
      a: { value_name?: string; value_struct?: { number: number; unit: string } | null },
      def?: CategoryAttribute
    ): { number: number | null; unit: string | null } {
      const struct = a.value_struct;
      if (struct && typeof struct === "object" && typeof struct.number === "number") {
        return { number: struct.number, unit: struct.unit || def?.default_unit || null };
      }
      const raw = (a.value_name || "").trim();
      const m = raw.match(/^([-\d.,]+)\s*([A-Za-z]+)?$/);
      if (m) {
        const n = parseFloat(m[1].replace(",", "."));
        return {
          number: isNaN(n) ? null : n,
          unit: m[2] || def?.default_unit || null,
        };
      }
      return { number: null, unit: def?.default_unit || null };
    }

    // s.attributes vem do backend já com value_struct quando disponível;
    // o backend é a fonte canônica do value_struct (multiget às vezes omite).
    const origAttrsById: Record<string, { value_struct?: { number: number; unit: string } | null }> = {};
    for (const a of preview.value.original.attributes as Array<Record<string, unknown>>) {
      const id = a.id as string;
      if (id) origAttrsById[id] = { value_struct: a.value_struct as { number: number; unit: string } | null };
    }

    const normalizedSuggestedAttrs = Array.from(
      s.attributes.reduce((attrs, attr) => {
        const canonicalId = canonicalPackageAttrId(attr.id);
        const id = canonicalId || attr.id;
        if (!attrs.has(id)) {
          attrs.set(id, {
            ...attr,
            id,
            name: canonicalId ? packageAttrName(canonicalId) : attr.name,
          });
        }
        return attrs;
      }, new Map<string, (typeof s.attributes)[number]>()).values()
    );

    editableAttrs.value = normalizedSuggestedAttrs.map((a) => {
      const def = defById[a.id];
      const base: EditableAttr = {
        id: a.id,
        name: a.name || origByName[a.id] || a.id,
        value_name: a.value_name || "",
        value_id: a.value_id,
        fromOriginal: true,
        required: !!(def?.tags?.required || def?.tags?.catalog_required),
        value_type: def?.value_type,
        values: def?.values,
        allowed_units: def?.allowed_units,
        default_unit: def?.default_unit,
        allow_custom_value: def?.tags?.allow_custom_value,
      };
      if (def?.value_type === "number_unit") {
        const fromOrig = origAttrsById[a.id];
        const merged = { ...a, value_struct: fromOrig?.value_struct ?? a.value_struct ?? undefined };
        const { number, unit } = parseNumUnit(merged, def);
        base.numberValue = number;
        base.unitValue = unit;
        if (number !== null && unit) {
          base.value_struct = { number, unit };
        }
      }
      return base;
    });
    const skuAttr = preview.value.original.attributes.find((a) => a.id === "SELLER_SKU");
    const sku = skuAttr?.value_name || "";
    // Garantia — lê de sale_terms.WARRANTY_TYPE e WARRANTY_TIME
    const origTerms = (preview.value.original.sale_terms || []) as Array<Record<string, unknown>>;
    const wType = origTerms.find((t) => t.id === "WARRANTY_TYPE");
    const wTime = origTerms.find((t) => t.id === "WARRANTY_TIME");
    const warrantyType = (wType?.value_name as string) || "Garantia do vendedor";
    const warrantyStruct = wTime?.value_struct as { number?: number; unit?: string } | undefined;
    const warrantyMonths = warrantyStruct?.number ?? 3;
    form.value = {
      price: s.price,
      available_quantity: s.available_quantity,
      seller_custom_field: sku,
      description: s.description || "",
      createPremium: s.listing_type_id === "gold_pro",
      createClassico: s.listing_type_id !== "gold_pro",
      localPickUp: !!s.shipping?.local_pick_up,
      warrantyType,
      warrantyMonths,
    };
    titles.value = [s.title];
  } catch (err: unknown) {
    let msg = "Anúncio não encontrado. Verifique o ID ou URL.";
    if (typeof err === "object" && err !== null) {
      const e = err as { response?: { data?: { detail?: unknown; message?: unknown } }; message?: string };
      const detail = e.response?.data?.detail ?? e.response?.data?.message;
      if (detail) msg = typeof detail === "string" ? detail : JSON.stringify(detail);
      else if (e.message) msg = e.message;
    }
    result.value = { error: msg };
    console.error("Clone preview falhou:", err);
  } finally {
    loading.value = false;
  }
}

function buildAttrsPayload(): Array<Record<string, unknown>> {
  const out: Array<Record<string, unknown>> = [];
  for (const a of editableAttrs.value) {
    const rawId = a.id.trim();
    const id = rawId.toLowerCase().startsWith("seller_") ? rawId : rawId.toUpperCase();
    if (!id) continue;
    // Marcado como "Não se aplica" — não envia
    if (attrNotApplicable.value.has(a.id)) continue;

    // number_unit — monta value_struct + value_name a partir dos campos numéricos
    if (a.value_type === "number_unit") {
      const num = a.numberValue;
      const unit = a.unitValue || a.default_unit;
      if (num === null || num === undefined || isNaN(num) || !unit) continue;
      out.push({
        id,
        value_name: `${num} ${unit}`,
        value_struct: { number: num, unit },
      });
      continue;
    }

    // ML rejeita value_name com mais de 255 chars (ex: OEM, PART_NUMBER com listas longas).
    // Safety net: trunca antes de enviar mesmo se o user passou do limite.
    const value = a.value_name.trim().slice(0, 255);
    if (!value) continue;
    const original = preview.value?.suggested.attributes.find((x) => x.id === id);
    const originalValueName = original?.value_name || "";
    const entry: Record<string, unknown> = { id, value_name: value };
    if (a.value_id && value === originalValueName) entry.value_id = a.value_id;
    if (a.value_struct) entry.value_struct = a.value_struct;
    out.push(entry);
  }
  return out;
}

function openMissingModal(attrs: MissingAttrDef[]) {
  modalInputs.value = attrs.map((attr) => ({
    id: attr.id,
    name: attr.name,
    value_type: attr.value_type,
    numValue: "",
    unit: attr.default_unit || attr.allowed_units?.[0]?.id || "",
    strValue: attr.value_type === "list" && attr.values?.length
      ? attr.values[0].id
      : attr.value_type === "boolean" ? "true" : "",
    allowed_units: attr.allowed_units || [],
    values: attr.values || [],
    hint: attr.hint || "",
  }));
  showMissingModal.value = true;
}

function confirmMissingAttrs() {
  for (const inp of modalInputs.value) {
    const existing = editableAttrs.value.find((a) => a.id === inp.id);
    if (inp.value_type === "number_unit") {
      const raw = inp.numValue.replace(",", ".");
      const num = parseFloat(raw);
      if (isNaN(num) || num <= 0 || !inp.unit) continue;
      const entry: EditableAttr = {
        id: inp.id, name: inp.name,
        value_name: `${num} ${inp.unit}`,
        value_struct: { number: num, unit: inp.unit },
        fromOriginal: false,
      };
      if (existing) Object.assign(existing, entry);
      else editableAttrs.value.push(entry);
    } else if (inp.value_type === "list") {
      const valId = inp.strValue;
      const valName = inp.values.find((v) => v.id === valId)?.name || valId;
      const entry: EditableAttr = { id: inp.id, name: inp.name, value_name: valName, value_id: valId, fromOriginal: false };
      if (existing) Object.assign(existing, entry);
      else editableAttrs.value.push(entry);
    } else {
      const valName = inp.strValue.trim();
      if (!valName) continue;
      const entry: EditableAttr = { id: inp.id, name: inp.name, value_name: valName, fromOriginal: false };
      if (existing) Object.assign(existing, entry);
      else editableAttrs.value.push(entry);
    }
  }
  showMissingModal.value = false;
  modalInputs.value = [];
  const names = packageAttrs.value.map((a) => a.name).join(", ");
  dimensionsMsg.value = `Medidas confirmadas (${names || "embalagem"}) — publicando anúncio...`;
  publishClone(true);
}

function toggleAccount(userId: number) {
  const next = new Set(selectedAccounts.value);
  if (next.has(userId)) next.delete(userId);
  else next.add(userId);
  selectedAccounts.value = next;
}

function accountNickname(userId: number): string {
  return auth.accounts.find((a) => a.user_id === userId)?.nickname || `Conta ${userId}`;
}

function initAccountSelection() {
  // Por padrão, marcar a conta ativa
  const active = auth.accounts.find((a) => a.is_active);
  if (active) selectedAccounts.value = new Set([active.user_id]);
}

onMounted(async () => {
  if (auth.accounts.length === 0) await auth.checkAuth();
  initAccountSelection();
});

const isMultiResult = computed(
  () => result.value !== null && "results" in (result.value as object)
);
const multiResult = computed(() =>
  isMultiResult.value ? (result.value as CloneMultiResultWithType) : null
);
const errorResult = computed(() =>
  result.value && "error" in (result.value as object)
    ? (result.value as { error: string }).error
    : null
);

// Lista de fotos editável: cada item é {id?, source?, preview}.
// `source` = URL externa que o ML vai baixar; `id` = foto já hospedada no ML.
// `preview` é a URL que mostramos na grid (qualquer uma das duas).
interface EditablePicture {
  id?: string;
  source?: string;
  preview: string;
}
const editablePictures = ref<EditablePicture[]>([]);
const newPicUrl = ref("");
const uploadingPic = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const draggingPicIndex = ref<number | null>(null);

function initPictures() {
  if (!preview.value) return;
  editablePictures.value = preview.value.suggested.pictures.map((p) => ({
    source: p.source,
    preview: p.source,
  }));
}

function removePicture(index: number) {
  editablePictures.value.splice(index, 1);
}

function addPicByUrl() {
  const url = newPicUrl.value.trim();
  if (!url) return;
  editablePictures.value.push({ source: url, preview: url });
  newPicUrl.value = "";
}

function triggerFileUpload() {
  fileInput.value?.click();
}

async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = input.files;
  if (!files || files.length === 0) return;
  uploadingPic.value = true;
  try {
    for (const file of Array.from(files)) {
      const resp = await uploadPicture(file);
      if (resp.status !== 201 && resp.status !== 200) continue;
      const remoteId = resp.data.id;
      const remoteUrl = resp.data.variations?.[0]?.secure_url;
      if (!remoteId && !remoteUrl) {
        console.warn("Upload retornou sem id nem URL — ignorando", resp.data);
        continue;
      }
      editablePictures.value.push({
        id: remoteId,
        source: remoteUrl || undefined,
        preview: remoteUrl || URL.createObjectURL(file),
      });
    }
  } catch (err) {
    console.error("Erro ao fazer upload:", err);
  } finally {
    uploadingPic.value = false;
    input.value = "";
  }
}

function onPicDragStart(index: number) {
  draggingPicIndex.value = index;
}
function onPicDragOver(e: DragEvent, index: number) {
  e.preventDefault();
  if (draggingPicIndex.value === null || draggingPicIndex.value === index) return;
  const arr = [...editablePictures.value];
  const [moved] = arr.splice(draggingPicIndex.value, 1);
  arr.splice(index, 0, moved);
  editablePictures.value = arr;
  draggingPicIndex.value = index;
}
function onPicDragEnd() {
  draggingPicIndex.value = null;
}

// Reinicializa fotos quando o preview muda
watch(preview, () => {
  if (preview.value) initPictures();
});

async function publishClone(skipConfirm = false) {
  if (!preview.value) return;

  if (selectedAccounts.value.size === 0) {
    result.value = { error: "Selecione pelo menos uma conta para publicar." };
    return;
  }

  if (!form.value.createPremium && !form.value.createClassico) {
    result.value = { error: "Selecione pelo menos um tipo de publicação (Premium ou Clássico)." };
    return;
  }

  const s = preview.value.suggested;
  const pics: Array<{ source: string } | { id: string }> = [];
  for (const p of editablePictures.value) {
    if (p.source) pics.push({ source: p.source });
    else if (p.id) pics.push({ id: p.id });
  }

  if (pics.length === 0) {
    result.value = { error: "Adicione pelo menos uma foto." };
    return;
  }

  const validTitles = titles.value.map((t) => t.trim()).filter((t) => t.length > 0);
  if (validTitles.length === 0) {
    result.value = { error: "Informe pelo menos um título." };
    return;
  }
  if (hasDuplicateTitles.value) {
    result.value = { error: "Corrija os títulos duplicados antes de continuar." };
    return;
  }

  // Confirmação — ação destrutiva (cria N anúncios em N contas)
  const accountCount = selectedAccounts.value.size;
  const typeCount = (form.value.createPremium ? 1 : 0) + (form.value.createClassico ? 1 : 0);
  const totalAds = accountCount * typeCount * validTitles.length;
  const types: string[] = [];
  if (form.value.createPremium) types.push("Premium");
  if (form.value.createClassico) types.push("Clássico");
  const accountNames = Array.from(selectedAccounts.value)
    .map((uid) => accountNickname(uid))
    .join(", ");
  const msg =
    `Vai criar ${totalAds} anúncio(s):\n\n` +
    `• ${accountCount} conta(s): ${accountNames}\n` +
    `• ${typeCount} tipo(s): ${types.join(", ")}\n` +
    `• ${validTitles.length} título(s)\n\n` +
    `Confirma?`;
  if (!skipConfirm && !confirm(msg)) return;

  creating.value = true;
  result.value = null;

  const buildPayload = (
    listingTypeId: string,
    price: number,
    title: string
  ): Record<string, unknown> => {
    // Reconstrói sale_terms (garantia) usando os valores editáveis do form;
    // mantém demais termos (ex: certificação) inalterados do source.
    const baseTerms = (s.sale_terms as Array<Record<string, unknown>>) || [];
    const otherTerms = baseTerms.filter(
      (t) => t.id !== "WARRANTY_TYPE" && t.id !== "WARRANTY_TIME"
    );
    const newSaleTerms: Array<Record<string, unknown>> = [
      ...otherTerms,
      { id: "WARRANTY_TYPE", value_name: form.value.warrantyType },
      {
        id: "WARRANTY_TIME",
        value_name: `${form.value.warrantyMonths} meses`,
        value_struct: { number: form.value.warrantyMonths, unit: "meses" },
      },
    ];

    const payload: Record<string, unknown> = {
      title,
      category_id: s.category_id,
      price,
      available_quantity: form.value.available_quantity,
      condition: s.condition,
      listing_type_id: listingTypeId,
      currency_id: "BRL",
      buying_mode: "buy_it_now",
      pictures: pics,
      attributes: buildAttrsPayload(),
      sale_terms: newSaleTerms,
      shipping: { mode: "me2", local_pick_up: form.value.localPickUp },
      channels: ["marketplace"],
    };
    if (form.value.seller_custom_field) payload.seller_custom_field = form.value.seller_custom_field;
    if (form.value.description) payload.description = form.value.description;
    // Compatibilidades — se o anúncio fonte tem, leva pro novo
    const compats = preview.value?.suggested.compatibilities || [];
    if (compats.length > 0) {
      payload.compatibilities = compats;
    }
    // Posições do anúncio-fonte (vazio se for de concorrente / sem acesso ao UP fonte)
    const positionRestrictions = preview.value?.suggested.position_restrictions || [];
    if (positionRestrictions.length > 0) {
      payload.position_restrictions = positionRestrictions;
    }
    return payload;
  };

  const jobs: Array<{
    type: "premium" | "classico";
    title: string;
    payload: Record<string, unknown>;
  }> = [];
  for (const title of validTitles) {
    if (form.value.createPremium) {
      jobs.push({
        type: "premium",
        title,
        payload: buildPayload("gold_pro", form.value.price, title),
      });
    }
    if (form.value.createClassico) {
      jobs.push({
        type: "classico",
        title,
        payload: buildPayload("gold_special", classicoPrice.value, title),
      });
    }
  }

  const userIds = Array.from(selectedAccounts.value);
  const merged: CloneMultiResultWithType = { total: 0, success: 0, results: [] };
  // Um id por publicação — agrupa todos os anúncios (títulos × tipos × contas)
  // num único card "Cópia de anúncio" no histórico.
  const batchId = crypto.randomUUID();

  try {
    const responses = await Promise.all(
      jobs.map((j) => createCloneMulti(j.payload, userIds, batchId))
    );
    responses.forEach((resp, idx) => {
      merged.total += resp.total;
      merged.success += resp.success;
      for (const r of resp.results) {
        merged.results.push({
          ...r,
          listing_type: jobs[idx].type,
          title_variant: jobs[idx].title,
        });
      }
    });

    // Se todas as tentativas falharam com erro de medidas de embalagem, abre o modal
    const allFailed = merged.success === 0 && merged.total > 0;
    const hasPkgError = merged.results.some(
      (r) => !r.success && r.error && isPackageDimsError(r.error)
    );
    if (allFailed && hasPkgError) {
      creating.value = false;
      openMissingModal(PACKAGE_DIMENSION_ATTRS);
      return;
    }

    result.value = merged;
    dimensionsMsg.value = null;
  } catch (err: unknown) {
    const e = err as { response?: { status?: number; data?: { detail?: unknown; message?: string } }; message?: string };
    const detail = e.response?.data?.detail;

    // 422 de atributos obrigatórios faltando (fluxo normal)
    if (
      e.response?.status === 422 &&
      detail !== null && typeof detail === "object" &&
      (detail as Record<string, unknown>).needs_input === true
    ) {
      const missing = (detail as { missing_attrs: MissingAttrDef[] }).missing_attrs ?? [];
      if (missing.length > 0) {
        creating.value = false;
        openMissingModal(missing);
        return;
      }
    }

    // 400 com erro de medidas de embalagem
    const errMsg = (typeof detail === "string" ? detail : "") || e.response?.data?.message || e.message || "";
    if (isPackageDimsError(errMsg)) {
      creating.value = false;
      openMissingModal(PACKAGE_DIMENSION_ATTRS);
      return;
    }

    result.value = { error: extractErrorMessage(err) };
    dimensionsMsg.value = null;
  } finally {
    creating.value = false;
  }
}

function formatPrice(price: number): string {
  return price.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function formatError(err: unknown): string {
  if (err == null) return "";
  if (typeof err === "string") return err;
  try {
    return JSON.stringify(err);
  } catch {
    return String(err);
  }
}
</script>

<template>
  <div>
    <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-gray-500 mb-0.5">
      Catálogo
    </p>
    <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight mb-2">Copiar Anúncio</h2>
    <p class="text-sm text-gray-500 mb-6 max-w-2xl">
      Cole a URL ou ID de um anúncio do Mercado Livre para copiar seus dados
    </p>

    <!-- Input -->
    <div class="bg-white rounded-2xl border shadow-sm p-4 mb-4">
      <div class="flex gap-3">
        <div class="relative flex-1">
          <Search :size="16" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            v-model="inputUrl"
            type="text"
            placeholder="URL ou ID do anúncio (ex: MLB1234567890 ou https://produto.mercadolivre.com.br/...)"
            class="w-full pl-9 pr-3 py-2.5 border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-brand-yellow focus:border-transparent bg-gray-50 transition-shadow"
            @keyup.enter="fetchPreview"
          />
        </div>
        <button
          @click="fetchPreview"
          :disabled="loading || !inputUrl.trim()"
          class="px-5 py-2.5 bg-meli-blue text-brand-yellow font-semibold rounded-xl text-sm hover:bg-meli-blue-dark transition-all hover:shadow-md flex items-center gap-2 disabled:opacity-50"
        >
          <Loader2 v-if="loading" :size="16" class="animate-spin" />
          <Search v-else :size="16" />
          Buscar
        </button>
      </div>
    </div>

    <!-- Mensagem amigável de medidas em andamento -->
    <div
      v-if="dimensionsMsg"
      class="mb-4 p-4 rounded-xl border bg-amber-50 border-amber-200 flex items-center gap-3"
    >
      <Loader2 :size="16" class="animate-spin text-amber-500 shrink-0" />
      <span class="text-sm text-amber-800 font-medium">{{ dimensionsMsg }}</span>
    </div>

    <!-- Result: error only -->
    <div
      v-if="errorResult"
      class="mb-4 p-4 rounded-xl border bg-red-50 border-red-200"
    >
      <div class="flex items-center gap-2">
        <AlertTriangle :size="18" class="text-red-600" />
        <span class="text-red-700 font-medium">{{ errorResult }}</span>
      </div>
    </div>

    <!-- Result: multi-account -->
    <div
      v-if="multiResult"
      class="mb-4 p-4 rounded-xl border"
      :class="multiResult.success === multiResult.total ? 'bg-green-50 border-green-200' : multiResult.success > 0 ? 'bg-yellow-50 border-yellow-200' : 'bg-red-50 border-red-200'"
    >
      <div class="flex items-center gap-2 font-medium mb-2">
        <CheckCircle v-if="multiResult.success === multiResult.total" :size="18" class="text-green-600" />
        <AlertTriangle v-else :size="18" class="text-yellow-600" />
        Publicado em {{ multiResult.success }}/{{ multiResult.total }} conta(s)
      </div>
      <div class="space-y-1">
        <div
          v-for="(r, idx) in multiResult.results"
          :key="`${r.user_id}-${r.listing_type}-${idx}`"
          class="flex items-center gap-2 text-sm"
        >
          <CheckCircle v-if="r.success" :size="14" class="text-green-600 flex-shrink-0" />
          <XCircle v-else :size="14" class="text-red-600 flex-shrink-0" />
          <span class="font-medium">{{ accountNickname(r.user_id) }}:</span>
          <span
            v-if="r.listing_type"
            class="text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full"
            :class="r.listing_type === 'premium' ? 'text-purple-700 bg-purple-100' : 'text-blue-700 bg-blue-100'"
          >{{ r.listing_type }}</span>
          <span
            v-if="r.title_variant"
            class="text-xs text-gray-600 italic truncate max-w-[16rem]"
            :title="r.title_variant"
          >"{{ r.title_variant }}"</span>
          <template v-if="r.success && r.item">
            <span class="text-gray-600">{{ r.item.id }}</span>
            <a
              v-if="r.item.permalink"
              :href="r.item.permalink"
              target="_blank"
              class="text-meli-blue hover:underline inline-flex items-center gap-1"
            >
              Ver <ExternalLink :size="12" />
            </a>
          </template>
          <span v-else class="text-red-600 text-xs truncate">{{ formatError(r.error) }}</span>
        </div>
      </div>
    </div>

    <!-- Preview + Edit -->
    <template v-if="preview">
      <!-- Barra de contexto: link para o anúncio original -->
      <div class="flex items-center justify-between bg-white rounded-xl border shadow-sm px-4 py-2 mb-4">
        <span class="text-xs text-gray-500">
          Copiando <span class="font-mono">{{ preview.original.id || "anúncio" }}</span>
        </span>
        <a
          :href="preview.original.permalink"
          target="_blank"
          class="text-sm text-meli-blue hover:underline flex items-center gap-1"
        >
          Ver original no ML <ExternalLink :size="14" />
        </a>
      </div>

      <!-- Linha 1: Dados básicos + Fotos -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <!-- Dados básicos -->
        <div class="bg-white rounded-xl border shadow-sm p-5">
          <h3 class="font-semibold mb-4 flex items-center gap-2">
            <Copy :size="18" class="text-meli-blue" />
            Dados do novo anúncio
          </h3>

          <div class="space-y-4">
            <div>
              <div class="flex items-center justify-between mb-1">
                <label class="block text-sm text-gray-600">
                  Título (max 60)
                  <span v-if="titles.length > 1" class="text-xs text-gray-400 ml-1">
                    — {{ validTitleCount }} variante{{ validTitleCount === 1 ? "" : "s" }}
                  </span>
                </label>
                <button
                  type="button"
                  @click="addTitle"
                  :disabled="titles.length >= MAX_TITLE_VARIANTS"
                  class="text-xs px-2 py-1 border rounded-lg hover:bg-gray-50 flex items-center gap-1 disabled:opacity-40 disabled:cursor-not-allowed"
                  :title="titles.length >= MAX_TITLE_VARIANTS ? `Limite de ${MAX_TITLE_VARIANTS} variantes` : 'Criar outro anúncio com título diferente'"
                >
                  <Plus :size="12" /> Outro título
                </button>
              </div>
              <div class="space-y-2">
                <div
                  v-for="(_, idx) in titles"
                  :key="idx"
                  class="flex items-center gap-2"
                >
                  <div class="flex-1 min-w-0">
                    <input
                      v-model="titles[idx]"
                      type="text"
                      maxlength="60"
                      :placeholder="idx === 0 ? 'Título principal' : `Variante ${idx + 1}`"
                      class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                      :class="isDuplicateTitle(idx) ? 'border-red-400 ring-1 ring-red-300' : ''"
                    />
                    <div class="flex items-center justify-between mt-0.5">
                      <span v-if="isDuplicateTitle(idx)" class="text-xs text-red-500">Título idêntico a outra variante</span>
                      <span v-else class="invisible text-xs">.</span>
                      <span class="text-xs text-gray-400">{{ titles[idx].length }}/60</span>
                    </div>
                  </div>
                  <button
                    v-if="idx > 0"
                    type="button"
                    @click="removeTitle(idx)"
                    class="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded flex-shrink-0"
                    title="Remover esta variante"
                  >
                    <Trash2 :size="14" />
                  </button>
                </div>
              </div>
              <p
                v-if="titles.length > 1"
                class="text-[11px] text-gray-400 mt-2"
              >
                Títulos muito parecidos numa mesma conta podem ser marcados como duplicados pelo ML.
              </p>
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm text-gray-600 mb-1">Preço (R$)</label>
                <input
                  v-model.number="form.price"
                  type="number"
                  step="0.01"
                  min="0"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                />
              </div>
              <div>
                <label class="block text-sm text-gray-600 mb-1">Estoque</label>
                <input
                  v-model.number="form.available_quantity"
                  type="number"
                  min="1"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                />
              </div>
            </div>

            <div>
              <label class="block text-sm text-gray-600 mb-1">SKU interno</label>
              <input
                v-model="form.seller_custom_field"
                type="text"
                placeholder="Seu SKU..."
                class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"/>
            </div>

            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm text-gray-600 mb-1">Tipo de garantia</label>
                <select
                  v-model="form.warrantyType"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                >
                  <option>Garantia do vendedor</option>
                  <option>Garantia de fábrica</option>
                  <option>Sem garantia</option>
                </select>
              </div>
              <div>
                <label class="block text-sm text-gray-600 mb-1">Tempo (meses)</label>
                <input
                  v-model.number="form.warrantyMonths"
                  type="number"
                  min="0"
                  class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                />
              </div>
            </div>

            <div>
              <label class="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  v-model="form.localPickUp"
                  class="rounded"
                />
                <span class="text-sm text-gray-700">Permitir retirada pessoal</span>
              </label>
            </div>

            <div>
              <label class="block text-sm text-gray-600 mb-2">Tipo de publicação</label>
              <div class="grid grid-cols-2 gap-2">
                <label
                  class="flex flex-col gap-1 px-3 py-2 border rounded-lg cursor-pointer hover:bg-gray-50 transition-colors"
                  :class="form.createPremium ? 'border-meli-blue bg-blue-50' : 'border-gray-200'"
                >
                  <div class="flex items-center gap-2">
                    <input type="checkbox" v-model="form.createPremium" class="rounded" />
                    <span class="text-sm font-medium">Premium</span>
                  </div>
                  <span class="text-xs text-gray-500 pl-6">{{ formatPrice(form.price) }}</span>
                </label>
                <label
                  class="flex flex-col gap-1 px-3 py-2 border rounded-lg cursor-pointer hover:bg-gray-50 transition-colors"
                  :class="form.createClassico ? 'border-meli-blue bg-blue-50' : 'border-gray-200'"
                >
                  <div class="flex items-center gap-2">
                    <input type="checkbox" v-model="form.createClassico" class="rounded" />
                    <span class="text-sm font-medium">Clássico</span>
                  </div>
                  <span class="text-xs text-gray-500 pl-6">
                    {{ formatPrice(classicoPrice) }}
                    <span class="text-[10px] text-green-600">(-5%)</span>
                  </span>
                </label>
              </div>
            </div>
          </div>
        </div>

        <!-- Fotos -->
        <div class="bg-white rounded-xl border shadow-sm p-5">
          <h4 class="font-semibold mb-1 flex items-center gap-2">
            <Image :size="16" class="text-meli-blue" />
            Fotos ({{ editablePictures.length }})
          </h4>
          <p class="text-xs text-gray-500 mb-3">
            Arraste pra reordenar. A primeira é a foto principal do anúncio.
          </p>

          <div
            v-if="editablePictures.length > 0"
            class="grid grid-cols-3 sm:grid-cols-4 gap-2 mb-3"
          >
            <div
              v-for="(pic, index) in editablePictures"
              :key="(pic.id || pic.source || '') + index"
              class="relative rounded-lg overflow-hidden border-2 group"
              :class="index === 0 ? 'border-meli-blue' : 'border-gray-200'"
              draggable="true"
              @dragstart="onPicDragStart(index)"
              @dragover="(e: DragEvent) => onPicDragOver(e, index)"
              @dragend="onPicDragEnd"
            >
              <img :src="pic.preview" class="w-full aspect-square object-cover" />
              <div
                v-if="index === 0"
                class="absolute top-1 left-1 text-[10px] uppercase font-semibold bg-meli-blue text-brand-yellow px-1.5 py-0.5 rounded-full"
              >Principal</div>
              <div class="absolute top-1 right-1 flex flex-col gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  type="button"
                  @click="removePicture(index)"
                  class="w-6 h-6 bg-red-500 hover:bg-red-600 rounded-full flex items-center justify-center"
                  title="Remover"
                >
                  <Trash2 :size="12" class="text-white" />
                </button>
              </div>
              <div class="absolute bottom-1 left-1 text-gray-100 bg-black/40 rounded p-0.5 cursor-grab">
                <GripVertical :size="12" />
              </div>
            </div>
          </div>

          <div v-else class="text-xs text-gray-400 italic mb-3 text-center py-6 border border-dashed rounded-lg">
            Nenhuma foto. Adicione abaixo.
          </div>

          <input
            ref="fileInput"
            type="file"
            accept="image/*"
            multiple
            class="hidden"
            @change="onFileSelected"
          />
          <div class="flex flex-col sm:flex-row gap-2">
            <button
              type="button"
              @click="triggerFileUpload"
              :disabled="uploadingPic"
              class="flex-1 px-3 py-2 border-2 border-dashed border-meli-blue rounded-lg text-sm text-meli-blue hover:bg-blue-50 transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <Loader2 v-if="uploadingPic" :size="14" class="animate-spin" />
              <Plus v-else :size="14" />
              {{ uploadingPic ? "Enviando..." : "Upload do PC" }}
            </button>
          </div>
          <div class="flex gap-2 mt-2">
            <input
              v-model="newPicUrl"
              type="text"
              placeholder="Ou cole a URL da imagem..."
              class="flex-1 px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              @keyup.enter="addPicByUrl"
            />
            <button
              type="button"
              @click="addPicByUrl"
              class="px-3 py-2 bg-meli-blue text-brand-yellow rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1"
            >
              <Plus :size="14" />
            </button>
          </div>
        </div>
      </div>

      <!-- Linha 2: Descrição (largura total) -->
      <div class="bg-white rounded-xl border shadow-sm p-5 mt-4">
        <label class="block text-sm font-semibold text-gray-700 mb-2">Descrição</label>
        <textarea
          v-model="form.description"
          rows="6"
          maxlength="50000"
          class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue resize-y"
        ></textarea>
      </div>

      <!-- Medidas da embalagem (card separado, só aparece quando preenchidas) -->
      <div
        v-if="packageAttrs.length > 0"
        class="bg-white rounded-xl border border-amber-200 shadow-sm p-5 mt-4"
      >
        <div class="flex items-center justify-between mb-3">
          <h4 class="font-semibold flex items-center gap-2">
            <Package :size="16" class="text-amber-500" />
            Medidas da embalagem
          </h4>
          <span class="text-xs text-gray-400">Preenchidas pelo modal de publicação</span>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-2">
          <div
            v-for="attr in packageAttrs"
            :key="attr.id"
            class="flex gap-2 items-center"
          >
            <label class="w-2/5 min-w-0 text-sm font-medium text-gray-700 truncate" :title="attr.name">
              {{ attr.name }}
            </label>
            <div v-if="attr.value_type === 'number_unit'" class="flex-1 min-w-0 flex gap-1">
              <input
                :value="attr.numberValue ?? ''"
                @input="(e) => {
                  const raw = (e.target as HTMLInputElement).value;
                  const n = raw === '' ? null : parseFloat(raw.replace(',', '.'));
                  attr.numberValue = (n !== null && !isNaN(n)) ? n : null;
                  attr.value_name = (attr.numberValue !== null && attr.unitValue)
                    ? `${attr.numberValue} ${attr.unitValue}` : '';
                }"
                type="number" step="any" min="0" placeholder="0"
                class="flex-1 min-w-0 px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              />
              <select
                v-if="(attr.allowed_units?.length ?? 0) > 1"
                :value="attr.unitValue || attr.default_unit || ''"
                @change="(e) => {
                  attr.unitValue = (e.target as HTMLSelectElement).value;
                  attr.value_name = (attr.numberValue !== null && attr.unitValue)
                    ? `${attr.numberValue} ${attr.unitValue}` : '';
                }"
                class="px-2 py-1.5 border border-gray-200 rounded text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue"
              >
                <option v-for="u in attr.allowed_units" :key="u.id" :value="u.id">{{ u.name }}</option>
              </select>
              <span v-else class="px-2 py-1.5 text-xs text-gray-500 bg-gray-100 rounded flex items-center whitespace-nowrap">
                {{ attr.unitValue || attr.default_unit || '—' }}
              </span>
            </div>
            <input
              v-else
              v-model="attr.value_name"
              type="text"
              class="flex-1 min-w-0 px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
            />
            <!-- Medidas são obrigatórias no ML; não tem botão pra remover/desaplicar. -->
          </div>
        </div>
      </div>

      <!-- Atributos (largura total, abaixo do anúncio original e novo) -->
      <div class="bg-white rounded-xl border shadow-sm p-5 mt-4">
        <div class="flex items-center justify-between mb-3">
          <h4 class="font-semibold flex items-center gap-2">
            <Tag :size="16" class="text-meli-blue" />
            Atributos ({{ regularAttrs.length }})
          </h4>
        </div>
        <p class="text-xs text-gray-500 mb-3">
          Copiados do anúncio original. Altere o valor se precisar.
        </p>
        <div v-if="regularAttrs.length === 0" class="text-xs text-gray-500">
          Nenhum atributo copiado do anúncio original.
        </div>
        <div v-else class="max-h-[34rem] overflow-y-auto pr-1 space-y-4">
          <!-- Grupos: obrigatórios primeiro, demais embaixo (separação pela tag da categoria) -->
          <div v-for="group in attrGroups" :key="group.key">
            <div class="flex items-center gap-2 mb-2">
              <span class="text-xs font-bold uppercase tracking-wide text-gray-500">{{ group.label }}</span>
              <span class="text-[11px] text-gray-400 tabular-nums">({{ group.items.length }})</span>
              <span
                v-if="group.key === 'required'"
                class="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700"
              >obrigatórios</span>
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              <!-- Cada atributo é um card empilhado: nome completo em cima (nunca truncado),
                   controle de valor em largura total embaixo. -->
              <div
                v-for="(attr, idx) in group.items"
                :key="`${attr.id}-${idx}`"
                class="rounded-lg border p-3 flex flex-col gap-2 transition-colors"
                :class="attrNotApplicable.has(attr.id)
                  ? 'border-gray-200 bg-gray-50'
                  : 'border-gray-200 hover:border-meli-blue/40'"
              >
            <!-- Atributo copiado do original: nome como label + valor editável -->
            <template v-if="attr.fromOriginal">
              <!-- Cabeçalho: nome (quebra linha, sem truncar) + toggle "Não se aplica" -->
              <div class="flex items-start justify-between gap-2">
                <span
                  class="text-sm font-medium leading-snug break-words"
                  :class="attrNotApplicable.has(attr.id) ? 'text-gray-400 line-through' : 'text-gray-700'"
                >
                  {{ attr.name }}
                </span>
                <button
                  v-if="attrNotApplicable.has(attr.id)"
                  type="button"
                  @click="cancelAttrNotApplicable(attr.id)"
                  class="flex items-center gap-1 text-[11px] px-2 py-0.5 bg-gray-200 text-gray-700 rounded-full hover:bg-gray-300 transition-colors flex-shrink-0"
                ><Trash2 :size="10" /> Não se aplica</button>
                <button
                  v-else
                  type="button"
                  @click="markAttrNotApplicable(attr.id)"
                  class="text-[11px] text-gray-400 hover:text-gray-600 transition-colors whitespace-nowrap flex-shrink-0"
                >Não se aplica</button>
              </div>

              <!-- Controle de valor (largura total) -->
              <div :class="{'opacity-40 pointer-events-none': attrNotApplicable.has(attr.id)}">
                <!-- number_unit: número + unidade -->
                <div v-if="attr.value_type === 'number_unit'" class="flex gap-1">
                  <input
                    :value="attr.numberValue ?? ''"
                    @input="(e) => {
                      const raw = (e.target as HTMLInputElement).value;
                      const n = raw === '' ? null : parseFloat(raw.replace(',', '.'));
                      attr.numberValue = (n !== null && !isNaN(n)) ? n : null;
                      attr.value_name = (attr.numberValue !== null && attr.unitValue)
                        ? `${attr.numberValue} ${attr.unitValue}` : '';
                    }"
                    type="number" step="any" min="0" placeholder="0"
                    class="flex-1 min-w-0 px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  />
                  <select
                    v-if="(attr.allowed_units?.length ?? 0) > 1"
                    :value="attr.unitValue || attr.default_unit || ''"
                    @change="(e) => {
                      attr.unitValue = (e.target as HTMLSelectElement).value;
                      attr.value_name = (attr.numberValue !== null && attr.unitValue)
                        ? `${attr.numberValue} ${attr.unitValue}` : '';
                    }"
                    class="px-2 py-1.5 border border-gray-200 rounded text-xs focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  >
                    <option v-for="u in attr.allowed_units" :key="u.id" :value="u.id">{{ u.name }}</option>
                  </select>
                  <span v-else class="px-2 py-1.5 text-xs text-gray-500 bg-gray-100 rounded flex items-center whitespace-nowrap">
                    {{ attr.unitValue || attr.default_unit || '—' }}
                  </span>
                </div>

                <!-- boolean: Sim / Não (ML rejeita texto livre nesses) -->
                <select
                  v-else-if="attr.value_type === 'boolean'"
                  :value="attr.value_name || ''"
                  @change="(e) => {
                    const v = (e.target as HTMLSelectElement).value;
                    attr.value_name = v;
                    // Se tem values do catálogo, casa pelo nome pra mandar value_id também
                    const sel = attr.values?.find((opt) => opt.name === v);
                    attr.value_id = sel?.id || undefined;
                  }"
                  class="w-full px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                >
                  <option value="">— Selecionar —</option>
                  <option value="Sim">Sim</option>
                  <option value="Não">Não</option>
                </select>

                <!-- list com valores fixos -->
                <select
                  v-else-if="(attr.values?.length ?? 0) > 0 && !attr.allow_custom_value"
                  :value="attr.value_id || ''"
                  @change="(e) => {
                    const id = (e.target as HTMLSelectElement).value;
                    const sel = attr.values?.find((v) => v.id === id);
                    attr.value_id = sel?.id || undefined;
                    attr.value_name = sel?.name || '';
                  }"
                  class="w-full px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                >
                  <option value="">— Selecionar —</option>
                  <option v-for="v in attr.values" :key="v.id" :value="v.id">{{ v.name }}</option>
                </select>

                <!-- texto livre (default) — ML aceita max 255 chars -->
                <template v-else>
                  <input
                    v-model="attr.value_name"
                    type="text"
                    :maxlength="255"
                    class="w-full px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                    :class="(attr.value_name?.length ?? 0) > 255 ? 'border-red-400' : ''"
                  />
                  <p
                    v-if="(attr.value_name?.length ?? 0) > 200"
                    class="text-[10px] mt-0.5"
                    :class="(attr.value_name?.length ?? 0) >= 255 ? 'text-red-600 font-medium' : 'text-amber-600'"
                  >
                    {{ attr.value_name?.length ?? 0 }}/255 caracteres
                  </p>
                </template>
              </div>
            </template>

            <!-- Atributo manual: nome + ID + valor editáveis -->
            <template v-else>
              <div class="flex items-start gap-2">
                <div class="flex-1 min-w-0 space-y-1">
                  <input
                    v-model="attr.name"
                    type="text"
                    placeholder="Nome do atributo"
                    class="w-full px-2 py-1 border rounded text-sm font-medium"
                  />
                  <input
                    v-model="attr.id"
                    type="text"
                    placeholder="ID"
                    class="w-full px-2 py-1 border rounded text-[10px] text-gray-600 font-mono uppercase"
                  />
                </div>
                <button
                  type="button"
                  @click="removeAttributeById(attr.id)"
                  class="p-1.5 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded flex-shrink-0"
                  title="Remover"
                >
                  <Trash2 :size="14" />
                </button>
              </div>
              <div>
                <input
                  v-model="attr.value_name"
                  type="text"
                  placeholder="Valor"
                  :maxlength="255"
                  class="w-full px-2 py-1.5 border rounded text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
                  :class="(attr.value_name?.length ?? 0) > 255 ? 'border-red-400' : ''"
                />
                <p
                  v-if="(attr.value_name?.length ?? 0) > 200"
                  class="text-[10px] mt-0.5"
                  :class="(attr.value_name?.length ?? 0) >= 255 ? 'text-red-600 font-medium' : 'text-amber-600'"
                >
                  {{ attr.value_name?.length ?? 0 }}/255 caracteres
                </p>
              </div>
            </template>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Compatibilidades (autopeças) -->
      <div
        v-if="preview.suggested.compatibilities && preview.suggested.compatibilities.length > 0"
        class="bg-white rounded-xl border shadow-sm p-5 mt-4"
      >
        <div class="flex items-center justify-between mb-3">
          <h4 class="font-semibold flex items-center gap-2">
            <Car :size="16" class="text-meli-blue" />
            Compatibilidades ({{ preview.suggested.compatibilities.length }} veículos)
          </h4>
          <span class="text-xs text-gray-400">
            Copiadas do anúncio original — aplicadas após criar
          </span>
        </div>
        <div class="space-y-1 max-h-60 overflow-y-auto">
          <div
            v-for="(c, idx) in preview.suggested.compatibilities"
            :key="(c.product_id || c.id || '') + idx"
            class="flex items-center gap-2 px-3 py-1.5 bg-gray-50 rounded-lg text-sm"
          >
            <Car :size="12" class="text-meli-blue flex-shrink-0" />
            <span class="flex-1 truncate">{{ c.name || c.product_id || c.id }}</span>
            <span
              v-if="c.note"
              class="text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700"
            >
              {{ c.note }}
            </span>
            <span class="text-[10px] text-gray-400 font-mono">
              {{ c.product_id || c.id }}
            </span>
          </div>
        </div>
      </div>

      <!-- Contas + Publicar (largura total, lado a lado em telas largas) -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-4">
        <!-- Account selector -->
        <div class="bg-white rounded-xl border shadow-sm p-5 lg:col-span-2">
          <h4 class="font-semibold mb-3 flex items-center gap-2">
            <Users :size="16" class="text-meli-blue" />
            Publicar nas contas ({{ selectedAccounts.size }}/{{ auth.accounts.length }})
          </h4>
          <div v-if="auth.accounts.length === 0" class="text-sm text-gray-500">
            Nenhuma conta conectada. Conecte uma conta no menu lateral.
          </div>
          <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <label
              v-for="account in auth.accounts"
              :key="account.user_id"
              class="flex items-center gap-3 px-3 py-2 border rounded-lg cursor-pointer hover:bg-gray-50 transition-colors"
              :class="selectedAccounts.has(account.user_id) ? 'border-meli-blue bg-blue-50' : 'border-gray-200'"
            >
              <input
                type="checkbox"
                :checked="selectedAccounts.has(account.user_id)"
                @change="toggleAccount(account.user_id)"
                class="rounded"
              />
              <span class="flex-1 text-sm font-medium truncate">{{ account.nickname }}</span>
              <span
                v-if="account.is_active"
                class="text-[10px] uppercase font-semibold text-blue-600 bg-blue-100 px-2 py-0.5 rounded-full"
              >ativa</span>
            </label>
          </div>
        </div>

        <!-- Publish button -->
        <div class="bg-white rounded-xl border shadow-sm p-5 flex flex-col justify-center">
          <button
            @click="publishClone()"
            :disabled="creating || selectedAccounts.size === 0 || !auth.can('clone_listing')"
            :title="!auth.can('clone_listing') ? 'Você não tem permissão para criar anúncios' : undefined"
            class="w-full px-4 py-3 bg-green-600 text-white rounded-xl font-medium hover:bg-green-700 transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
          >
            <Loader2 v-if="creating" :size="18" class="animate-spin" />
            <Copy v-else :size="18" />
            {{ creating ? "Criando..." : `Publicar em ${selectedAccounts.size} conta(s)` }}
          </button>
          <p class="text-xs text-gray-500 text-center mt-2">
            {{
              selectedAccounts.size *
              ((form.createPremium ? 1 : 0) + (form.createClassico ? 1 : 0)) *
              Math.max(validTitleCount, 1)
            }} anúncio(s) serão criados
            <span v-if="validTitleCount > 1">
              ({{ validTitleCount }} títulos × tipos × contas)
            </span>
          </p>
        </div>
      </div>
    </template>

    <!-- ── Modal: atributos obrigatórios ausentes ──────────────────── -->
    <Teleport to="body">
      <div
        v-if="showMissingModal"
        class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm"
      >
        <div class="bg-white rounded-2xl shadow-2xl w-full max-w-md max-h-[90vh] flex flex-col">
          <!-- Header -->
          <div class="px-6 pt-6 pb-4 border-b">
            <div class="flex items-start gap-3">
              <div class="w-9 h-9 rounded-full bg-amber-100 flex items-center justify-center shrink-0">
                <AlertTriangle :size="18" class="text-amber-600" />
              </div>
              <div>
                <h3 class="font-semibold text-gray-900">Informações obrigatórias</h3>
                <p class="text-sm text-gray-500 mt-0.5">
                  Preencha os campos abaixo para poder publicar o anúncio.
                </p>
              </div>
            </div>
          </div>

          <!-- Fields -->
          <div class="px-6 py-4 overflow-y-auto space-y-4 flex-1">
            <div
              v-for="inp in modalInputs"
              :key="inp.id"
              class="space-y-1"
            >
              <label class="block text-sm font-medium text-gray-700">
                {{ inp.name }}
                <span class="text-red-500 ml-0.5">*</span>
                <span v-if="inp.hint" class="font-normal text-gray-400 ml-1 text-xs">— {{ inp.hint }}</span>
              </label>

              <!-- number_unit: número + unidade -->
              <div v-if="inp.value_type === 'number_unit'">
                <div class="flex gap-2">
                  <input
                    type="text"
                    :inputmode="isIntegerUnit(inp.unit) ? 'numeric' : 'decimal'"
                    :placeholder="isIntegerUnit(inp.unit) ? 'Ex: 20' : 'Ex: 500 ou 1,5'"
                    v-model="inp.numValue"
                    class="flex-1 px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="numFieldError(inp) ? 'border-red-400 focus:ring-red-300' : 'border-gray-300 focus:ring-meli-blue'"
                  />
                  <select
                    v-if="inp.allowed_units.length > 0"
                    v-model="inp.unit"
                    class="px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white min-w-[80px]"
                  >
                    <option v-for="u in inp.allowed_units" :key="u.id" :value="u.id">{{ u.name }}</option>
                  </select>
                  <span v-else class="px-3 py-2 border rounded-lg text-sm bg-gray-50 text-gray-500">
                    {{ inp.unit || "un" }}
                  </span>
                </div>
                <p v-if="numFieldError(inp)" class="mt-1 text-xs text-red-500 flex items-center gap-1">
                  <span>⚠</span> {{ numFieldError(inp) }}
                </p>
              </div>

              <!-- list: dropdown de valores predefinidos -->
              <select
                v-else-if="inp.value_type === 'list' && inp.values.length > 0"
                v-model="inp.strValue"
                class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
              >
                <option v-for="v in inp.values" :key="v.id" :value="v.id">{{ v.name }}</option>
              </select>

              <!-- boolean -->
              <select
                v-else-if="inp.value_type === 'boolean'"
                v-model="inp.strValue"
                class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue bg-white"
              >
                <option value="">Selecione...</option>
                <option value="true">Sim</option>
                <option value="false">Não</option>
              </select>

              <!-- string / number -->
              <input
                v-else
                type="text"
                placeholder="Informe o valor"
                v-model="inp.strValue"
                class="w-full px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
              />
            </div>
          </div>

          <!-- Footer -->
          <div class="px-6 py-4 border-t flex gap-3">
            <button
              type="button"
              @click="showMissingModal = false"
              class="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
            >
              Cancelar
            </button>
            <button
              type="button"
              @click="confirmMissingAttrs"
              :disabled="!missingAttrsValid"
              class="flex-1 px-4 py-2.5 bg-green-600 text-white rounded-lg text-sm font-medium hover:bg-green-700 disabled:opacity-50 transition-colors flex items-center justify-center gap-2"
            >
              <Copy :size="15" />
              Confirmar e Publicar
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

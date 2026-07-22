<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import {
  getPromotions, getCoupons, createCoupon, updateCoupon, endCoupon,
  type Promotion, type Coupon, type CouponInput,
} from "@/api/promotions";
import {
  Loader2, RefreshCw, Tag, Percent, CalendarClock, PackageX, ChevronRight, Info,
  TicketPercent, Users, Plus, Pencil, Trash2, Settings2, X,
} from "lucide-vue-next";

const router = useRouter();
const auth = useAuthStore();

const promotions = ref<Promotion[]>([]);
const coupons = ref<Coupon[]>([]);
const loading = ref(false);
const errorMsg = ref<string | null>(null);

function fmtMoney(v: number | null): string {
  if (v == null) return "—";
  return v.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
// Quanto do orçamento já foi gasto (0–100). budget pode vir nulo.
function budgetUsedPct(c: Coupon): number | null {
  if (c.budget == null || c.budget <= 0 || c.remaining_budget == null) return null;
  return Math.min(100, Math.round(((c.budget - c.remaining_budget) / c.budget) * 100));
}

const activeAccount = computed(() => auth.accounts.find((a) => a.is_active) || null);

// Troca a conta da operação (mesma conta ativa global usada nas outras telas).
// Ao trocar, recarrega promoções/cupons da conta escolhida.
const switching = ref(false);
async function onSwitchAccount(e: Event) {
  const userId = Number((e.target as HTMLSelectElement).value);
  if (!userId || userId === activeAccount.value?.user_id) return;
  switching.value = true;
  try {
    await auth.switchAccount(userId);
    await load();
  } finally {
    switching.value = false;
  }
}

// Rótulos amigáveis para os tipos de promoção do ML.
const TYPE_LABELS: Record<string, string> = {
  DEAL: "Oferta do dia",
  DOD: "Oferta do dia",
  LIGHTNING: "Oferta relâmpago",
  PRICE_DISCOUNT: "Desconto de preço",
  MARKETPLACE_CAMPAIGN: "Campanha do ML",
  SELLER_CAMPAIGN: "Campanha própria",
  SMART: "Promoção inteligente",
  PRICE_MATCHING: "Equiparação de preço",
  VOLUME: "Desconto por volume",
  PRE_NEGOTIATED: "Pré-negociada",
  SELLER_COUPON_CAMPAIGN: "Cupom",
  UNHEALTHY_STOCK: "Estoque parado",
};
function typeLabel(t: string): string {
  return TYPE_LABELS[t] || t;
}

// Explicação de cada tipo (o que descobrimos no scan da API). `tag` resume quem
// controla o preço; `desc` detalha o comportamento.
interface TypeInfo {
  tag: string;
  tagClass: string;
  desc: string;
}
const TYPE_INFO: Record<string, TypeInfo> = {
  DEAL: {
    tag: "Você define o preço",
    tagClass: "bg-brand-yellow-soft text-brand-black dark:bg-zinc-800 dark:text-brand-yellow",
    desc: "Oferta. Você escolhe o preço promocional de cada anúncio, dentro de uma faixa mínima/máxima definida pelo ML. Há um preço sugerido pré-preenchido.",
  },
  DOD: {
    tag: "Você define o preço",
    tagClass: "bg-brand-yellow-soft text-brand-black dark:bg-zinc-800 dark:text-brand-yellow",
    desc: "Oferta do dia. Você escolhe o preço promocional dentro da faixa permitida pelo ML.",
  },
  LIGHTNING: {
    tag: "Preço + estoque",
    tagClass: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
    desc: "Oferta relâmpago. Você escolhe o preço (dentro da faixa) e reserva uma quantidade de estoque para a queima; quando a cota reservada vende, a oferta encerra mesmo que o anúncio tenha mais estoque.",
  },
  PRICE_DISCOUNT: {
    tag: "Você define o preço",
    tagClass: "bg-brand-yellow-soft text-brand-black dark:bg-zinc-800 dark:text-brand-yellow",
    desc: "Desconto de preço direto. Você define o preço promocional dentro da faixa permitida.",
  },
  SMART: {
    tag: "ML define o desconto",
    tagClass: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
    desc: "Promoção inteligente. O ML define o desconto e co-financia parte dele (parte ML / parte vendedor); você só inclui ou remove anúncios. O preço promocional não é editável.",
  },
  UNHEALTHY_STOCK: {
    tag: "ML define o desconto",
    tagClass: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
    desc: "Campanha de estoque parado no Full. O ML seleciona itens encalhados e define o desconto; você apenas inclui ou remove anúncios.",
  },
  SELLER_COUPON_CAMPAIGN: {
    tag: "Cupom % fixo",
    tagClass: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300",
    desc: "Cupom de desconto. Percentual fixo aplicado na compra; você só adiciona os anúncios elegíveis. O desconto não é editável por anúncio.",
  },
};
function typeInfo(t: string): TypeInfo {
  return (
    TYPE_INFO[t] || {
      tag: "Promoção",
      tagClass: "bg-gray-100 text-gray-600 dark:bg-zinc-800 dark:text-gray-300",
      desc: "Promoção do Mercado Livre. Abra para ver os anúncios elegíveis e gerenciar a participação.",
    }
  );
}

const STATUS_LABELS: Record<string, string> = {
  started: "Em andamento",
  pending: "Pendente",
  candidate: "Disponível",
  finished: "Encerrada",
};
function statusLabel(s: string | null): string {
  return s ? STATUS_LABELS[s] || s : "—";
}
function statusClass(s: string | null): string {
  if (s === "started") return "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300";
  if (s === "pending" || s === "candidate")
    return "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300";
  return "bg-gray-100 text-gray-500 dark:bg-zinc-800 dark:text-gray-400";
}

function fmtDate(iso: string | null): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y.slice(2)}`;
}

async function load() {
  loading.value = true;
  errorMsg.value = null;
  try {
    [promotions.value, coupons.value] = await Promise.all([getPromotions(), getCoupons()]);
  } catch (err: any) {
    errorMsg.value = err?.response?.data?.detail || "Erro ao carregar promoções.";
    console.error("Erro ao carregar promoções:", err);
  } finally {
    loading.value = false;
  }
}

function open(p: Promotion) {
  router.push({
    name: "promotion-detail",
    params: { id: p.id },
    query: {
      type: p.type,
      name: p.name || "",
      start: p.start_date || "",
      finish: p.finish_date || "",
      deadline: p.deadline_date || "",
    },
  });
}

// ───────────────────────── Cupom: criar / editar / encerrar / itens ─────────────
const canManage = computed(() => auth.can("manage_promotions"));

const formOpen = ref(false);
const editingId = ref<string | null>(null); // null = criar
const saving = ref(false);
const formMsg = ref<string | null>(null);

function blankForm(): CouponInput {
  return {
    name: "", sub_type: "FIXED_PERCENTAGE", start_date: "", finish_date: "",
    fixed_percentage: null, fixed_amount: null, min_purchase_amount: null,
    max_purchase_amount: null, budget: null, redeems_per_user: null, partial_coupon_code: null,
  };
}
const form = reactive<CouponInput>(blankForm());

function resetForm(src?: Coupon) {
  Object.assign(form, blankForm());
  if (src) {
    form.name = src.name || "";
    form.sub_type = (src.sub_type as CouponInput["sub_type"]) || "FIXED_PERCENTAGE";
    form.start_date = (src.start_date || "").slice(0, 10); // ISO → yyyy-mm-dd p/ <input type=date>
    form.finish_date = (src.finish_date || "").slice(0, 10);
    form.fixed_percentage = src.fixed_percentage;
    form.min_purchase_amount = src.min_purchase_amount;
    form.max_purchase_amount = src.max_purchase_amount;
    form.budget = src.budget;
    form.redeems_per_user = src.redeems_per_user;
  }
}

function openCreate() {
  editingId.value = null;
  formMsg.value = null;
  resetForm();
  formOpen.value = true;
}
function openEdit(c: Coupon) {
  editingId.value = c.id;
  formMsg.value = null;
  resetForm(c);
  formOpen.value = true;
}

const formValid = computed(() => {
  if (!form.name || !form.start_date || !form.finish_date || !form.budget) return false;
  return form.sub_type === "FIXED_PERCENTAGE"
    ? form.fixed_percentage != null && form.fixed_percentage > 0
    : form.fixed_amount != null && form.fixed_amount > 0;
});

async function submitForm() {
  if (!formValid.value) return;
  saving.value = true;
  formMsg.value = null;
  try {
    // Manda só o campo de desconto do sub_type escolhido.
    const payload: CouponInput = { ...form };
    if (payload.sub_type === "FIXED_PERCENTAGE") payload.fixed_amount = null;
    else payload.fixed_percentage = null;
    const res = editingId.value
      ? await updateCoupon(editingId.value, payload)
      : await createCoupon(payload);
    if (res.ok) {
      formOpen.value = false;
      await load();
    } else {
      // Body de escrita ainda em validação → mostrar resposta crua do ML.
      formMsg.value = `ML recusou (status ${res.status}): ${JSON.stringify(res.data ?? res.error)}`;
    }
  } catch (err: any) {
    formMsg.value = err?.response?.data?.detail || "Erro ao salvar o cupom.";
  } finally {
    saving.value = false;
  }
}

const ending = ref<string | null>(null);
async function confirmEnd(c: Coupon) {
  if (!confirm(`Encerrar o cupom "${c.name || c.id}"? Isso remove a campanha no ML.`)) return;
  ending.value = c.id;
  try {
    const res = await endCoupon(c.id);
    if (res.ok) await load();
    else alert(`ML recusou (status ${res.status}): ${JSON.stringify(res.data ?? res.error)}`);
  } finally {
    ending.value = null;
  }
}

// Reusa a tela de itens da promoção (cupom = não editável → fluxo incluir/remover).
function openItems(c: Coupon) {
  router.push({
    name: "promotion-detail",
    params: { id: c.id },
    query: { type: "SELLER_COUPON_CAMPAIGN", name: c.name || "", start: c.start_date || "", finish: c.finish_date || "" },
  });
}

onMounted(async () => {
  if (auth.accounts.length === 0) await auth.checkAuth();
  await load();
});
</script>

<template>
  <div>
    <!-- Header -->
    <div class="flex items-end justify-between gap-3 mb-5">
      <div>
        <p class="text-[11px] font-bold uppercase tracking-[0.18em] text-gray-500">Ofertas e campanhas</p>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Gerenciar Promoções</h2>
        <!-- Seletor de conta: troca a conta da operação direto na página. -->
        <div v-if="auth.accounts.length" class="flex items-center gap-2 mt-1.5">
          <span class="text-sm text-gray-500">Conta</span>
          <div class="relative inline-flex items-center">
            <select
              :value="activeAccount?.user_id"
              @change="onSwitchAccount"
              :disabled="switching || loading"
              class="text-sm font-semibold rounded-lg border pl-3 pr-8 py-1.5 disabled:opacity-50
                     dark:border-zinc-700 dark:bg-zinc-900"
            >
              <option v-for="a in auth.accounts" :key="a.user_id" :value="a.user_id">
                {{ a.nickname }}
              </option>
            </select>
            <Loader2 v-if="switching" :size="14" class="animate-spin text-gray-400 absolute right-2 pointer-events-none" />
          </div>
        </div>
      </div>
      <button
        @click="load"
        :disabled="loading"
        class="inline-flex items-center gap-2 px-3 py-2 rounded-xl text-sm font-semibold border
               hover:bg-gray-50 disabled:opacity-50 transition-colors
               dark:border-zinc-700 dark:hover:bg-zinc-800"
      >
        <RefreshCw :size="14" :class="loading ? 'animate-spin' : ''" />
        Atualizar
      </button>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-16">
      <Loader2 :size="32" class="animate-spin text-meli-blue" />
    </div>

    <div
      v-else-if="errorMsg"
      class="bg-amber-50 border border-amber-200 rounded-xl p-6 text-center text-amber-800"
    >
      {{ errorMsg }}
    </div>

    <div
      v-else-if="promotions.length === 0 && coupons.length === 0 && !canManage"
      class="bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 p-12 text-center text-gray-500"
    >
      <PackageX :size="32" class="mx-auto mb-3 opacity-60" />
      Nenhuma promoção ou cupom disponível para esta conta no momento.
    </div>

    <div v-else class="space-y-8">
      <!-- Cupons — tratados à parte das promoções (não têm fluxo elegível/participando) -->
      <section v-if="coupons.length || canManage">
        <div class="flex items-center justify-between gap-3 mb-3">
          <h3 class="text-sm font-bold uppercase tracking-wider text-gray-500 flex items-center gap-2">
            <TicketPercent :size="16" /> Cupons <span class="text-gray-400 font-normal">({{ coupons.length }})</span>
          </h3>
          <button
            v-if="canManage"
            @click="openCreate"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-sm font-semibold bg-brand-black text-brand-yellow hover:opacity-90 transition-opacity dark:bg-brand-yellow dark:text-brand-black"
          >
            <Plus :size="14" /> Novo cupom
          </button>
        </div>

        <div
          v-if="!coupons.length"
          class="bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 p-8 text-center text-sm text-gray-500"
        >
          Nenhum cupom ativo. Crie um com “Novo cupom”.
        </div>

        <div v-else class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 stagger-children">
          <div
            v-for="c in coupons"
            :key="c.id"
            class="bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 shadow-sm p-5"
          >
            <div class="flex items-start justify-between gap-3 mb-3">
              <div class="p-2 rounded-xl bg-purple-100 dark:bg-purple-900/30 flex-shrink-0">
                <TicketPercent :size="18" class="text-purple-700 dark:text-purple-300" />
              </div>
              <span class="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full" :class="statusClass(c.status)">
                {{ statusLabel(c.status) }}
              </span>
            </div>
            <h3 class="font-bold text-gray-900 dark:text-gray-100 leading-snug line-clamp-2" :title="c.name || 'Cupom'">
              {{ c.name || "Cupom" }}
            </h3>

            <!-- Regras do cupom -->
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1 mt-2 text-xs text-gray-600 dark:text-gray-300">
              <span v-if="c.fixed_percentage != null" class="inline-flex items-center gap-1 font-semibold">
                <Percent :size="13" /> {{ c.fixed_percentage }}% off
              </span>
              <span v-if="c.min_purchase_amount != null" title="Valor mínimo de compra">
                mín. {{ fmtMoney(c.min_purchase_amount) }}
              </span>
              <span v-if="c.max_purchase_amount != null" title="Teto de desconto por compra">
                · teto {{ fmtMoney(c.max_purchase_amount) }}
              </span>
            </div>

            <!-- Orçamento consumido -->
            <div v-if="budgetUsedPct(c) != null" class="mt-3">
              <div class="flex justify-between text-[11px] text-gray-500 mb-1">
                <span>Orçamento</span>
                <span class="tabular-nums">{{ fmtMoney((c.budget ?? 0) - (c.remaining_budget ?? 0)) }} de {{ fmtMoney(c.budget) }}</span>
              </div>
              <div class="h-1.5 rounded-full bg-gray-200 dark:bg-zinc-700 overflow-hidden">
                <div class="h-full bg-purple-500 rounded-full" :style="{ width: budgetUsedPct(c) + '%' }" />
              </div>
            </div>

            <div class="flex items-center gap-3 mt-4 text-xs text-gray-500">
              <span v-if="c.start_date || c.finish_date" class="inline-flex items-center gap-1" title="Início → término">
                <CalendarClock :size="13" /> {{ fmtDate(c.start_date) }} – {{ fmtDate(c.finish_date) }}
              </span>
              <span v-if="c.used_coupons != null" class="inline-flex items-center gap-1" title="Resgates">
                <Users :size="13" /> {{ c.used_coupons }} usados
              </span>
            </div>

            <!-- Ações -->
            <div v-if="canManage" class="flex items-center gap-2 mt-4 pt-3 border-t dark:border-zinc-800">
              <button
                @click="openItems(c)"
                class="inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
                title="Gerenciar anúncios elegíveis"
              >
                <Settings2 :size="13" /> Itens
              </button>
              <button
                @click="openEdit(c)"
                class="inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1.5 rounded-lg border hover:bg-gray-50 transition-colors dark:border-zinc-700 dark:hover:bg-zinc-800"
              >
                <Pencil :size="13" /> Editar
              </button>
              <button
                @click="confirmEnd(c)"
                :disabled="ending === c.id"
                class="ml-auto inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1.5 rounded-lg border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50 transition-colors dark:border-red-900/50 dark:hover:bg-red-900/20"
                title="Encerrar cupom"
              >
                <Loader2 v-if="ending === c.id" :size="13" class="animate-spin" />
                <Trash2 v-else :size="13" /> Encerrar
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- Grade de promoções -->
      <section v-if="promotions.length">
        <h3 class="text-sm font-bold uppercase tracking-wider text-gray-500 mb-3 flex items-center gap-2">
          <Tag :size="16" /> Promoções <span class="text-gray-400 font-normal">({{ promotions.length }})</span>
        </h3>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 stagger-children">
      <button
        v-for="p in promotions"
        :key="p.id"
        @click="open(p)"
        class="group relative z-0 hover:z-30 focus-within:z-30 text-left bg-white dark:bg-brand-black-soft rounded-2xl border dark:border-zinc-800 shadow-sm
               p-5 hover:shadow-glow hover:border-brand-yellow-dark transition-all"
      >
        <div class="flex items-start justify-between gap-3 mb-3">
          <div class="p-2 rounded-xl bg-brand-yellow-soft dark:bg-zinc-800 flex-shrink-0">
            <Tag :size="18" class="text-brand-black dark:text-brand-yellow" />
          </div>
          <span
            class="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
            :class="statusClass(p.status)"
          >
            {{ statusLabel(p.status) }}
          </span>
        </div>
        <h3 class="font-bold text-gray-900 dark:text-gray-100 leading-snug line-clamp-2" :title="p.name || typeLabel(p.type)">
          {{ p.name || typeLabel(p.type) }}
        </h3>

        <!-- Tipo + tooltip explicativo no hover -->
        <div class="group/tip relative inline-flex items-center gap-1.5 mt-1.5">
          <span class="text-xs font-semibold text-gray-700 dark:text-gray-300">{{ typeLabel(p.type) }}</span>
          <Info :size="13" class="text-gray-400 group-hover/tip:text-brand-black dark:group-hover/tip:text-brand-yellow transition-colors" />
          <div
            class="invisible opacity-0 group-hover/tip:visible group-hover/tip:opacity-100 transition-opacity
                   absolute left-0 top-full mt-1.5 z-30 w-72 p-3 rounded-xl shadow-xl text-left
                   bg-white border border-gray-200 dark:bg-brand-black-soft dark:border-zinc-700"
          >
            <span
              class="inline-block text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full mb-1.5"
              :class="typeInfo(p.type).tagClass"
            >
              {{ typeInfo(p.type).tag }}
            </span>
            <p class="text-xs leading-relaxed text-gray-600 dark:text-gray-300 normal-case">
              {{ typeInfo(p.type).desc }}
            </p>
          </div>
        </div>

        <!-- Contagem elegíveis / participando (paging.total, sem baixar os itens) -->
        <div v-if="p.candidate_count != null || p.started_count != null" class="mt-3 text-xs text-gray-500 dark:text-gray-400">
          <span v-if="p.candidate_count != null">elegíveis: {{ p.candidate_count.toLocaleString("pt-BR") }}</span>
          <span v-if="p.candidate_count != null && p.started_count != null"> / </span>
          <span v-if="p.started_count != null">participando: {{ p.started_count.toLocaleString("pt-BR") }}</span>
        </div>

        <div class="flex items-center gap-3 mt-4 text-xs text-gray-500">
          <span v-if="p.start_date || p.finish_date" class="inline-flex items-center gap-1" title="Início → término da promoção">
            <CalendarClock :size="13" />
            {{ fmtDate(p.start_date) }} – {{ fmtDate(p.finish_date) }}
          </span>
          <span v-if="p.fixed_percentage != null" class="inline-flex items-center gap-1">
            <Percent :size="13" />
            {{ p.fixed_percentage }}% off
          </span>
          <ChevronRight
            :size="16"
            class="ml-auto text-gray-300 group-hover:text-brand-black dark:group-hover:text-brand-yellow group-hover:translate-x-0.5 transition-all"
          />
        </div>
      </button>
        </div>
      </section>
    </div>

    <!-- Modal de form de cupom (criar/editar) -->
    <Teleport to="body">
      <div
        v-if="formOpen"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50"
        @click.self="formOpen = false"
      >
        <div class="w-full max-w-md rounded-2xl shadow-xl overflow-hidden bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700 max-h-[90vh] overflow-y-auto">
          <div class="flex items-center justify-between p-5 pb-3">
            <h3 class="text-base font-bold text-gray-900 dark:text-gray-100 flex items-center gap-2">
              <TicketPercent :size="18" class="text-purple-600 dark:text-purple-300" />
              {{ editingId ? "Editar cupom" : "Novo cupom" }}
            </h3>
            <button @click="formOpen = false" class="p-1 rounded-md hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-400">
              <X :size="18" />
            </button>
          </div>

          <div class="px-5 pb-5 space-y-3">
            <div>
              <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Nome</label>
              <input v-model="form.name" type="text" placeholder="Ex.: Novos seguidores"
                class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
            </div>

            <div>
              <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Tipo de desconto</label>
              <div class="flex gap-2">
                <button
                  v-for="opt in [{ k: 'FIXED_PERCENTAGE', l: '% percentual' }, { k: 'FIXED_AMOUNT', l: 'R$ fixo' }]"
                  :key="opt.k"
                  type="button"
                  @click="form.sub_type = opt.k as CouponInput['sub_type']"
                  class="flex-1 px-3 py-2 text-sm rounded-xl border font-semibold transition-colors"
                  :class="form.sub_type === opt.k ? 'bg-brand-black text-brand-yellow border-brand-black dark:bg-brand-yellow dark:text-brand-black' : 'dark:border-zinc-700 text-gray-600 dark:text-gray-300'"
                >
                  {{ opt.l }}
                </button>
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3">
              <div v-if="form.sub_type === 'FIXED_PERCENTAGE'">
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">% de desconto</label>
                <input v-model.number="form.fixed_percentage" type="number" min="1" max="99" step="1"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
              <div v-else>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Valor (R$)</label>
                <input v-model.number="form.fixed_amount" type="number" min="1" step="0.01"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
              <div>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Orçamento (R$)</label>
                <input v-model.number="form.budget" type="number" min="1" step="0.01"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Compra mínima (R$)</label>
                <input v-model.number="form.min_purchase_amount" type="number" min="0" step="0.01"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
              <div>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Teto desconto (R$)</label>
                <input v-model.number="form.max_purchase_amount" type="number" min="0" step="0.01"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Início</label>
                <input v-model="form.start_date" type="date"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
              <div>
                <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">Término</label>
                <input v-model="form.finish_date" type="date"
                  class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
              </div>
            </div>

            <div>
              <label class="block text-xs font-semibold text-gray-600 dark:text-gray-300 mb-1">
                Código do cupom <span class="font-normal text-gray-400">(opcional — vazio = aplica sozinho)</span>
              </label>
              <input v-model="form.partial_coupon_code" type="text" placeholder="Ex.: BEMVINDO"
                class="w-full px-3 py-2 text-sm rounded-xl border dark:border-zinc-700 dark:bg-zinc-900" />
            </div>

            <p v-if="formMsg" class="text-xs text-red-600 dark:text-red-400 break-words bg-red-50 dark:bg-red-900/20 rounded-lg p-2">
              {{ formMsg }}
            </p>
          </div>

          <div class="flex justify-end gap-2 p-5 pt-0">
            <button @click="formOpen = false" :disabled="saving"
              class="px-3 py-2 rounded-lg text-sm border hover:bg-gray-50 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800 dark:text-gray-200">
              Cancelar
            </button>
            <button @click="submitForm" :disabled="!formValid || saving"
              class="px-3 py-2 rounded-lg text-sm font-semibold inline-flex items-center gap-1.5 bg-brand-black text-brand-yellow hover:opacity-90 disabled:opacity-50 dark:bg-brand-yellow dark:text-brand-black">
              <Loader2 v-if="saving" :size="14" class="animate-spin" />
              {{ editingId ? "Salvar" : "Criar cupom" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

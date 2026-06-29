<script setup lang="ts">
import { computed } from "vue";
import type { ReputationAccount, ReputationMetric } from "@/api/dashboard";
import {
  MessageSquare,
  Gavel,
  ShoppingBag,
  Truck,
  CheckCircle,
  AlertTriangle,
  Award,
} from "lucide-vue-next";

const props = defineProps<{ account: ReputationAccount }>();

// 5 níveis de reputação do ML, do pior pro melhor
const REPUTATION_LEVELS = [
  { id: "1_red", color: "#FECACA", active: "#DC2626" },
  { id: "2_orange", color: "#FED7AA", active: "#EA580C" },
  { id: "3_yellow", color: "#FEF08A", active: "#CA8A04" },
  { id: "4_light_green", color: "#D9F99D", active: "#65A30D" },
  { id: "5_green", color: "#BBF7D0", active: "#16A34A" },
];

const activeLevelIndex = computed(() =>
  REPUTATION_LEVELS.findIndex((l) => l.id === props.account.level_id)
);

const powerLabel = computed(() => {
  const power = (props.account.power_seller_status || "").toLowerCase();
  if (power === "platinum") return "MercadoLíder Platinum";
  if (power === "gold") return "MercadoLíder Gold";
  if (power === "silver") return "MercadoLíder";
  // Sem power seller — descreve pela cor
  const id = props.account.level_id || "";
  if (id.includes("green")) return "Reputação verde";
  if (id.includes("yellow")) return "Reputação amarela";
  if (id.includes("orange")) return "Reputação laranja";
  if (id.includes("red")) return "Reputação vermelha";
  return "Reputação";
});

interface MetricCard {
  key: keyof ReputationAccount["metrics"];
  label: string;
  icon: typeof MessageSquare;
  subject: string;
}

const CARDS: MetricCard[] = [
  { key: "claims", label: "Reclamações", icon: MessageSquare, subject: "suas vendas" },
  { key: "mediations", label: "Mediações", icon: Gavel, subject: "suas vendas" },
  { key: "cancellations", label: "Canceladas por você", icon: ShoppingBag, subject: "suas vendas" },
  { key: "delayed_handling", label: "Envios atrasados", icon: Truck, subject: "suas vendas com envio" },
];

function fmtPct(rate: number): string {
  const pct = rate * 100;
  if (pct === 0) return "0%";
  if (pct < 0.01) return "<0,01%";
  return pct.toLocaleString("pt-BR", { maximumFractionDigits: 2 }) + "%";
}
function fmtLimit(limit: number): string {
  const pct = limit * 100;
  return pct.toLocaleString("pt-BR", { maximumFractionDigits: 1 }) + "%";
}

</script>

<template>
  <div class="bg-white rounded-2xl border shadow-sm p-4 hover:shadow-md transition-shadow">
    <!-- Header: nickname à esquerda + widget de reputação à direita (estilo ML) -->
    <div class="flex items-start justify-between gap-4 mb-4 flex-wrap">
      <div class="flex-shrink-0">
        <h4 class="font-semibold text-sm">{{ account.nickname }}</h4>
        <p class="text-[11px] text-gray-500">
          {{ account.sales_completed.toLocaleString("pt-BR") }} vendas no período
        </p>
      </div>

      <!-- Widget de reputação -->
      <div class="flex-1 min-w-[240px] max-w-sm border rounded-lg p-3 bg-gray-50">
        <div class="flex items-center gap-2 mb-2">
          <Award
            :size="16"
            :style="activeLevelIndex >= 0 ? { color: REPUTATION_LEVELS[activeLevelIndex].active } : { color: '#9ca3af' }"
          />
          <span
            class="text-sm font-semibold"
            :style="activeLevelIndex >= 0 ? { color: REPUTATION_LEVELS[activeLevelIndex].active } : { color: '#6b7280' }"
          >
            {{ activeLevelIndex >= 0 ? powerLabel : "Sem classificação" }}
          </span>
        </div>

        <!-- 5 barras coloridas -->
        <div class="flex gap-1 items-end h-3">
          <div
            v-for="(lv, idx) in REPUTATION_LEVELS"
            :key="lv.id"
            class="flex-1 rounded-sm transition-all"
            :class="idx === activeLevelIndex ? 'h-3' : 'h-1.5'"
            :style="{
              background: idx === activeLevelIndex ? lv.active : lv.color,
              opacity: activeLevelIndex < 0 ? 0.6 : idx === activeLevelIndex ? 1 : 0.65
            }"
          ></div>
        </div>

        <p class="text-[10px] text-gray-500 mt-2">
          {{ activeLevelIndex >= 0
            ? "Você aparece assim para os compradores."
            : "O Mercado Livre ainda não classificou essa conta." }}
        </p>
      </div>
    </div>

    <div v-if="account.error" class="text-xs text-red-500">
      Erro ao carregar reputação: {{ account.error }}
    </div>

    <!-- Cards de métrica -->
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3">
      <div
        v-for="c in CARDS"
        :key="c.key"
        class="border rounded-lg p-3"
      >
        <div class="flex items-start justify-between">
          <h5 class="text-xs font-semibold text-gray-700">{{ c.label }}</h5>
        </div>

        <div class="flex items-center gap-2 mt-2">
          <component :is="c.icon" :size="18" class="text-gray-500" />
          <component
            :is="(account.metrics[c.key] as ReputationMetric).within_limit ? CheckCircle : AlertTriangle"
            :size="14"
            :class="(account.metrics[c.key] as ReputationMetric).within_limit ? 'text-green-500' : 'text-red-500'"
          />
          <span class="text-2xl font-bold leading-tight">
            {{ fmtPct((account.metrics[c.key] as ReputationMetric).rate) }}
          </span>
        </div>

        <p class="text-[11px] text-gray-500 mt-1">
          São {{ (account.metrics[c.key] as ReputationMetric).value }} de {{ c.subject }}
        </p>

        <span
          class="inline-block mt-1.5 text-[10px] px-2 py-0.5 rounded-full"
          :class="(account.metrics[c.key] as ReputationMetric).within_limit
            ? 'bg-gray-100 text-gray-700'
            : 'bg-red-50 text-red-700 border border-red-200'"
        >
          {{ (account.metrics[c.key] as ReputationMetric).within_limit ? 'Abaixo de' : 'Acima de' }}
          {{ fmtLimit((account.metrics[c.key] as ReputationMetric).limit) }} permitido
        </span>
      </div>
    </div>
  </div>
</template>

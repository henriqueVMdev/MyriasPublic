<script setup lang="ts">
defineProps<{ status: string }>();

const colors: Record<string, string> = {
  active: "bg-green-100 text-green-700",
  paused: "bg-yellow-100 text-yellow-700",
  closed: "bg-red-100 text-red-700",
  under_review: "bg-orange-100 text-orange-700",
};

const dots: Record<string, string> = {
  active: "bg-green-500",
  paused: "bg-yellow-500",
  closed: "bg-red-500",
  under_review: "bg-orange-500",
};

const labels: Record<string, string> = {
  active: "Ativo",
  paused: "Pausado",
  closed: "Encerrado",
  under_review: "Em revisão",
};
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 pl-1.5 pr-2 py-0.5 rounded-full text-xs font-semibold"
    :class="colors[status] || 'bg-gray-100 text-gray-700'"
  >
    <span class="w-1.5 h-1.5 rounded-full" :class="dots[status] || 'bg-gray-400'"></span>
    {{ labels[status] || status }}
  </span>
</template>

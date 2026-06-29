<script setup lang="ts">
import { computed } from "vue";
import { Crown, Tag } from "lucide-vue-next";

const props = defineProps<{ type: string }>();

const isPremium = computed(() => props.type === "gold_pro");
const label = computed(() => (isPremium.value ? "Premium" : "Clássico"));
</script>

<template>
  <span
    class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium whitespace-nowrap"
    :class="
      isPremium
        ? 'bg-amber-100 text-amber-700'
        : 'bg-gray-100 text-gray-600'
    "
  >
    <Crown v-if="isPremium" :size="10" />
    <Tag v-else :size="10" />
    {{ label }}
  </span>
</template>

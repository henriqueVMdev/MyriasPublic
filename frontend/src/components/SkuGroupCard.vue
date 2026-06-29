<script setup lang="ts">
import { useRouter } from "vue-router";
import type { SkuGroup } from "@/api/bulk";
import StatusBadge from "./StatusBadge.vue";
import ListingTypeBadge from "./ListingTypeBadge.vue";
import { Package, ChevronRight } from "lucide-vue-next";

defineProps<{ group: SkuGroup }>();
const router = useRouter();

function formatPrice(price: number): string {
  return price.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}
</script>

<template>
  <div
    class="bg-white rounded-lg border shadow-sm hover:shadow-md transition-shadow cursor-pointer"
    @click="router.push(`/bulk/sku/${encodeURIComponent(group.sku)}`)"
  >
    <div class="p-4">
      <div class="flex items-center justify-between mb-3">
        <div class="flex items-center gap-2">
          <Package :size="18" class="text-meli-blue" />
          <span class="font-semibold text-gray-900">{{ group.sku }}</span>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-sm text-gray-500 bg-gray-100 px-2 py-0.5 rounded-full">
            {{ group.count }} {{ group.count === 1 ? "anúncio" : "anúncios" }}
          </span>
          <ChevronRight :size="18" class="text-gray-400" />
        </div>
      </div>

      <!-- Preview items -->
      <div class="space-y-2">
        <div
          v-for="item in group.items_preview"
          :key="item.id"
          class="flex items-center gap-3 text-sm"
        >
          <img
            :src="item.thumbnail"
            class="w-8 h-8 rounded object-cover bg-gray-100 flex-shrink-0"
          />
          <span class="text-gray-600 truncate flex-1">{{ item.title }}</span>
          <span class="text-gray-900 font-medium flex-shrink-0">{{ formatPrice(item.price) }}</span>
          <div class="flex items-center gap-1.5 flex-shrink-0">
            <ListingTypeBadge :type="item.listing_type_id ?? ''" />
            <StatusBadge :status="item.status" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

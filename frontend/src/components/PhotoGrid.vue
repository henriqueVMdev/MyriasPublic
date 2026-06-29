<script setup lang="ts">
import { ref } from "vue";
import type { Picture } from "@/types/item";
import { GripVertical, Trash2, Plus, Star } from "lucide-vue-next";

const props = defineProps<{
  pictures: Picture[];
  readonly?: boolean;
}>();

const emit = defineEmits<{
  update: [pictures: Array<{ id?: string; source?: string }>];
}>();

const localPictures = ref([...props.pictures]);
const draggingIndex = ref<number | null>(null);

function onDragStart(index: number) {
  draggingIndex.value = index;
}

function onDragOver(e: DragEvent, index: number) {
  e.preventDefault();
  if (draggingIndex.value === null || draggingIndex.value === index) return;
  const items = [...localPictures.value];
  const [moved] = items.splice(draggingIndex.value, 1);
  items.splice(index, 0, moved);
  localPictures.value = items;
  draggingIndex.value = index;
}

function onDragEnd() {
  draggingIndex.value = null;
  emitUpdate();
}

function removePicture(index: number) {
  localPictures.value.splice(index, 1);
  emitUpdate();
}

function emitUpdate() {
  emit(
    "update",
    localPictures.value.map((p) => ({ id: p.id }))
  );
}

const newUrl = ref("");

function addByUrl() {
  if (!newUrl.value.trim()) return;
  // Add as a virtual picture for preview
  localPictures.value.push({
    id: `new-${Date.now()}`,
    url: newUrl.value,
    secure_url: newUrl.value,
    size: "",
    max_size: "",
  });
  const pics = localPictures.value.map((p) =>
    p.id.startsWith("new-") ? { source: p.secure_url } : { id: p.id }
  );
  emit("update", pics);
  newUrl.value = "";
}
</script>

<template>
  <div>
    <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-3">
      <div
        v-for="(pic, index) in localPictures"
        :key="pic.id"
        class="relative group rounded-lg border-2 overflow-hidden aspect-square bg-gray-50"
        :class="index === 0 ? 'border-meli-blue' : 'border-gray-200'"
        :draggable="!readonly"
        @dragstart="onDragStart(index)"
        @dragover="(e) => onDragOver(e, index)"
        @dragend="onDragEnd"
      >
        <img
          :src="pic.secure_url || pic.url"
          class="w-full h-full object-cover"
        />

        <!-- Principal badge -->
        <div
          v-if="index === 0"
          class="absolute top-1 left-1 bg-meli-blue text-white text-[10px] px-1.5 py-0.5 rounded flex items-center gap-0.5"
        >
          <Star :size="10" /> Principal
        </div>

        <!-- Actions overlay -->
        <div
          v-if="!readonly"
          class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center gap-2"
        >
          <button class="p-1.5 bg-white rounded-lg cursor-grab">
            <GripVertical :size="14" class="text-gray-600" />
          </button>
          <button
            @click="removePicture(index)"
            class="p-1.5 bg-white rounded-lg hover:bg-red-50"
          >
            <Trash2 :size="14" class="text-red-500" />
          </button>
        </div>
      </div>
    </div>

    <!-- Add by URL -->
    <div v-if="!readonly" class="mt-3 flex gap-2">
      <input
        v-model="newUrl"
        type="text"
        placeholder="URL da nova imagem..."
        class="flex-1 px-3 py-2 border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-meli-blue"
        @keyup.enter="addByUrl"
      />
      <button
        @click="addByUrl"
        class="px-4 py-2 bg-meli-blue text-white rounded-lg text-sm hover:bg-meli-blue-dark transition-colors flex items-center gap-1"
      >
        <Plus :size="16" /> Adicionar
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  Bot,
  BrainCircuit,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleDollarSign,
  Database,
  Loader2,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  Trash2,
  Wrench,
  X,
} from "lucide-vue-next";
import {
  createAiMemory,
  createAiSkill,
  deleteAiMemory,
  deleteAiSkill,
  getAiCommands,
  getAiMemories,
  getAiModels,
  getAiOverview,
  getAiSkills,
  refreshAiModels,
  setAiModel,
  updateAiMemory,
  updateAiSkill,
  type AiCommand,
  type AiMemory,
  type AiModelInfo,
  type AiOverview,
  type AiSkill,
} from "@/api/aiAdmin";

type Tab = "overview" | "models" | "commands" | "customization";

const tab = ref<Tab>("overview");
const loading = ref(true);
const error = ref("");
const overview = ref<AiOverview | null>(null);
const models = ref<AiModelInfo[]>([]);
const selectedModel = ref("");
const memories = ref<AiMemory[]>([]);
const skills = ref<AiSkill[]>([]);

const modelSearch = ref("");
const freeOnly = ref(false);
const compatibleOnly = ref(false);
const providerFilter = ref("");
const refreshingModels = ref(false);
const savingModel = ref("");
const expandedModel = ref<string | null>(null);

const commands = ref<AiCommand[]>([]);
const commandTotal = ref(0);
const commandOffset = ref(0);
const commandLimit = 25;
const commandSearch = ref("");
const commandUser = ref("");
const commandStatus = ref("");
const commandsLoading = ref(false);
const expandedCommand = ref<number | null>(null);

const editorOpen = ref(false);
const editorKind = ref<"memory" | "skill">("memory");
const editingId = ref<number | null>(null);
const editorSaving = ref(false);
const editorError = ref("");
const memoryForm = ref({ title: "", content: "", enabled: true });
const skillForm = ref({ name: "", description: "", instructions: "", enabled: true });

const tabs: { key: Tab; label: string; icon: typeof Bot }[] = [
  { key: "overview", label: "Agente", icon: Bot },
  { key: "models", label: "Modelos", icon: Sparkles },
  { key: "commands", label: "Comandos e gastos", icon: CircleDollarSign },
  { key: "customization", label: "Memórias e skills", icon: BrainCircuit },
];

const providers = computed(() =>
  Array.from(new Set(models.value.map((model) => model.id.split("/")[0]).filter(Boolean))).sort()
);

const filteredModels = computed(() => {
  const query = modelSearch.value.trim().toLowerCase();
  return models.value.filter((model) => {
    if (freeOnly.value && !model.free) return false;
    if (compatibleOnly.value && !model.toolCompatible) return false;
    if (providerFilter.value && !model.id.startsWith(providerFilter.value + "/")) return false;
    return !query
      || model.name.toLowerCase().includes(query)
      || model.id.toLowerCase().includes(query)
      || model.description.toLowerCase().includes(query);
  });
});

function apiError(e: any, fallback: string): string {
  return e?.response?.data?.detail || e?.message || fallback;
}

async function loadInitial() {
  loading.value = true;
  error.value = "";
  try {
    const [overviewData, catalogData, memoryData, skillData] = await Promise.all([
      getAiOverview(),
      getAiModels(),
      getAiMemories(),
      getAiSkills(),
    ]);
    overview.value = overviewData;
    models.value = catalogData.models;
    selectedModel.value = catalogData.selected;
    memories.value = memoryData;
    skills.value = skillData;
    await loadCommands();
  } catch (e: any) {
    error.value = apiError(e, "Erro ao carregar o painel de IA.");
  } finally {
    loading.value = false;
  }
}

async function reloadOverview() {
  overview.value = await getAiOverview();
}

async function refreshCatalog() {
  refreshingModels.value = true;
  error.value = "";
  try {
    const data = await refreshAiModels();
    models.value = data.models;
    selectedModel.value = data.selected;
    await reloadOverview();
  } catch (e: any) {
    error.value = apiError(e, "Erro ao atualizar o catálogo.");
  } finally {
    refreshingModels.value = false;
  }
}

async function activateModel(model: AiModelInfo) {
  if (!model.toolCompatible || savingModel.value) return;
  savingModel.value = model.id;
  error.value = "";
  try {
    const data = await setAiModel(model.id);
    selectedModel.value = data.selected;
    if (overview.value) overview.value.agent.selected_model = data.selected;
  } catch (e: any) {
    error.value = apiError(e, "Erro ao alterar o modelo.");
  } finally {
    savingModel.value = "";
  }
}

async function loadCommands(reset = false) {
  if (reset) commandOffset.value = 0;
  commandsLoading.value = true;
  try {
    const data = await getAiCommands({
      userId: commandUser.value ? Number(commandUser.value) : undefined,
      status: commandStatus.value || undefined,
      q: commandSearch.value.trim() || undefined,
      offset: commandOffset.value,
      limit: commandLimit,
    });
    commands.value = data.commands;
    commandTotal.value = data.paging.total;
  } catch (e: any) {
    error.value = apiError(e, "Erro ao carregar comandos.");
  } finally {
    commandsLoading.value = false;
  }
}

function changeCommandPage(direction: -1 | 1) {
  commandOffset.value = Math.max(0, commandOffset.value + direction * commandLimit);
  loadCommands();
}

function openMemory(memory?: AiMemory) {
  editorKind.value = "memory";
  editingId.value = memory?.id ?? null;
  memoryForm.value = {
    title: memory?.title ?? "",
    content: memory?.content ?? "",
    enabled: memory?.enabled ?? true,
  };
  editorError.value = "";
  editorOpen.value = true;
}

function openSkill(skill?: AiSkill) {
  editorKind.value = "skill";
  editingId.value = skill?.id ?? null;
  skillForm.value = {
    name: skill?.name ?? "",
    description: skill?.description ?? "",
    instructions: skill?.instructions ?? "",
    enabled: skill?.enabled ?? true,
  };
  editorError.value = "";
  editorOpen.value = true;
}

async function saveEditor() {
  editorSaving.value = true;
  editorError.value = "";
  try {
    if (editorKind.value === "memory") {
      if (editingId.value) await updateAiMemory(editingId.value, memoryForm.value);
      else await createAiMemory(memoryForm.value);
      memories.value = await getAiMemories();
    } else {
      if (editingId.value) await updateAiSkill(editingId.value, skillForm.value);
      else await createAiSkill(skillForm.value);
      skills.value = await getAiSkills();
    }
    editorOpen.value = false;
    await reloadOverview();
  } catch (e: any) {
    editorError.value = apiError(e, "Erro ao salvar.");
  } finally {
    editorSaving.value = false;
  }
}

async function toggleMemory(memory: AiMemory) {
  await updateAiMemory(memory.id, {
    title: memory.title,
    content: memory.content,
    enabled: !memory.enabled,
  });
  memories.value = await getAiMemories();
}

async function toggleSkill(skill: AiSkill) {
  await updateAiSkill(skill.id, {
    name: skill.name,
    description: skill.description,
    instructions: skill.instructions,
    enabled: !skill.enabled,
  });
  skills.value = await getAiSkills();
}

async function removeMemory(memory: AiMemory) {
  if (!confirm(`Excluir a memória "${memory.title}"?`)) return;
  await deleteAiMemory(memory.id);
  memories.value = await getAiMemories();
  await reloadOverview();
}

async function removeSkill(skill: AiSkill) {
  if (!confirm(`Excluir a skill "${skill.name}"?`)) return;
  await deleteAiSkill(skill.id);
  skills.value = await getAiSkills();
  await reloadOverview();
}

function pricePerMillion(value?: string): string {
  if (value == null || value === "") return "—";
  const price = Number(value);
  if (!Number.isFinite(price)) return value;
  if (price === 0) return "Grátis";
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(price * 1_000_000);
}

function formatCost(value: number | string | null | undefined): string {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 4,
    maximumFractionDigits: 8,
  }).format(Number(value || 0));
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat("pt-BR").format(value || 0);
}

function formatDate(value?: string): string {
  if (!value) return "—";
  return new Date(value).toLocaleString("pt-BR");
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

onMounted(loadInitial);
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
      <div>
        <div class="flex items-center gap-2">
          <BrainCircuit :size="28" class="text-indigo-600 dark:text-indigo-400" />
          <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Agentes de IA</h2>
        </div>
        <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
          Modelos, custos, auditoria e comportamento do assistente.
        </p>
      </div>
      <div
        v-if="selectedModel"
        class="rounded-xl border px-4 py-2 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800"
      >
        <div class="text-[10px] uppercase tracking-wider font-bold text-gray-400">Modelo ativo</div>
        <div class="text-sm font-semibold text-gray-900 dark:text-gray-100">{{ selectedModel }}</div>
      </div>
    </div>

    <div class="flex gap-1 overflow-x-auto p-1 rounded-xl bg-gray-100 dark:bg-zinc-900">
      <button
        v-for="item in tabs"
        :key="item.key"
        @click="tab = item.key"
        class="inline-flex items-center gap-2 whitespace-nowrap px-4 py-2 rounded-lg text-sm font-semibold transition-colors"
        :class="tab === item.key
          ? 'bg-white text-gray-900 shadow-sm dark:bg-zinc-800 dark:text-white'
          : 'text-gray-500 hover:text-gray-900 dark:hover:text-gray-200'"
      >
        <component :is="item.icon" :size="16" /> {{ item.label }}
      </button>
    </div>

    <p v-if="error" class="text-sm text-red-600 bg-red-50 dark:bg-red-900/30 rounded-xl px-4 py-3">
      {{ error }}
    </p>

    <div v-if="loading" class="flex items-center gap-2 py-12 justify-center text-gray-500">
      <Loader2 :size="20" class="animate-spin" /> Carregando painel de IA…
    </div>

    <template v-else>
      <!-- Visão geral -->
      <div v-if="tab === 'overview'" class="space-y-6">
        <div class="grid sm:grid-cols-2 xl:grid-cols-4 gap-3">
          <div class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <div class="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-gray-400">
              <Sparkles :size="15" /> Catálogo
            </div>
            <div class="text-2xl font-extrabold mt-2">{{ formatNumber(overview?.catalog_count || 0) }}</div>
            <div class="text-xs text-gray-500">modelos disponíveis</div>
          </div>
          <div class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <div class="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-gray-400">
              <CircleDollarSign :size="15" /> Gasto total
            </div>
            <div class="text-2xl font-extrabold mt-2">{{ formatCost(overview?.usage.total_cost) }}</div>
            <div class="text-xs text-gray-500">{{ formatNumber(overview?.usage.total_tokens || 0) }} tokens</div>
          </div>
          <div class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <div class="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-gray-400">
              <Database :size="15" /> Memórias
            </div>
            <div class="text-2xl font-extrabold mt-2">{{ overview?.memories_count || 0 }}</div>
            <div class="text-xs text-gray-500">itens configurados</div>
          </div>
          <div class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <div class="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-gray-400">
              <BrainCircuit :size="15" /> Skills
            </div>
            <div class="text-2xl font-extrabold mt-2">{{ overview?.skills_count || 0 }}</div>
            <div class="text-xs text-gray-500">instruções especializadas</div>
          </div>
        </div>

        <div class="grid xl:grid-cols-3 gap-5">
          <div class="xl:col-span-2 rounded-2xl border p-5 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <div class="flex items-start gap-3">
              <div class="p-2 rounded-xl bg-indigo-100 text-indigo-600 dark:bg-indigo-950 dark:text-indigo-300">
                <Bot :size="24" />
              </div>
              <div>
                <h3 class="font-bold text-lg">{{ overview?.agent.name }}</h3>
                <p class="text-sm text-gray-500 dark:text-gray-400">{{ overview?.agent.description }}</p>
                <div class="flex flex-wrap gap-2 mt-3">
                  <span class="text-xs px-2 py-1 rounded-lg bg-gray-100 dark:bg-zinc-800">
                    até {{ overview?.agent.max_iterations }} etapas por comando
                  </span>
                  <span class="text-xs px-2 py-1 rounded-lg bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">
                    confirmação humana para escritas
                  </span>
                </div>
              </div>
            </div>
          </div>

          <div class="rounded-2xl border p-5 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800">
            <h3 class="font-bold">Uso por colaborador</h3>
            <div class="mt-3 space-y-3 max-h-64 overflow-y-auto">
              <div v-for="user in overview?.usage.by_user || []" :key="user.app_user_id" class="flex items-center gap-3">
                <div class="min-w-0 flex-1">
                  <div class="text-sm font-semibold truncate">{{ user.display_name || user.username }}</div>
                  <div class="text-xs text-gray-400">{{ user.commands }} comandos · {{ formatNumber(user.tokens) }} tokens</div>
                </div>
                <div class="text-sm font-bold">{{ formatCost(user.cost) }}</div>
              </div>
              <p v-if="!overview?.usage.by_user.length" class="text-sm text-gray-500">Nenhum comando registrado.</p>
            </div>
          </div>
        </div>

        <div>
          <div class="flex items-center gap-2 mb-3">
            <Wrench :size="19" />
            <h3 class="font-bold text-lg">Ferramentas do agente</h3>
            <span class="text-xs text-gray-400">somente leitura nesta página</span>
          </div>
          <div class="grid md:grid-cols-2 xl:grid-cols-3 gap-3">
            <div
              v-for="tool in overview?.tools || []"
              :key="tool.name"
              class="rounded-xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800"
            >
              <div class="flex items-center gap-2">
                <code class="text-xs font-bold">{{ tool.name }}</code>
                <span
                  class="ml-auto text-[10px] font-bold uppercase px-2 py-0.5 rounded-full"
                  :class="tool.write
                    ? 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'
                    : 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-300'"
                >
                  {{ tool.write ? "escrita" : "leitura" }}
                </span>
              </div>
              <p class="text-xs text-gray-500 dark:text-gray-400 mt-2">{{ tool.description }}</p>
              <p v-if="tool.permission" class="text-[11px] text-gray-400 mt-2">
                Permissão: {{ tool.permission }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- Catálogo -->
      <div v-else-if="tab === 'models'" class="space-y-4">
        <div class="flex flex-col xl:flex-row gap-3">
          <div class="relative flex-1">
            <Search :size="17" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              v-model="modelSearch"
              placeholder="Buscar por nome, ID ou descrição…"
              class="w-full pl-10 pr-3 py-2.5 rounded-xl border bg-white border-gray-200 dark:bg-zinc-900 dark:border-zinc-700"
            />
          </div>
          <select v-model="providerFilter" class="px-3 py-2.5 rounded-xl border bg-white border-gray-200 dark:bg-zinc-900 dark:border-zinc-700">
            <option value="">Todos os fornecedores</option>
            <option v-for="provider in providers" :key="provider" :value="provider">{{ provider }}</option>
          </select>
          <label class="inline-flex items-center gap-2 px-3 py-2 rounded-xl border border-gray-200 dark:border-zinc-700 text-sm">
            <input v-model="freeOnly" type="checkbox" /> Somente gratuitos
          </label>
          <label class="inline-flex items-center gap-2 px-3 py-2 rounded-xl border border-gray-200 dark:border-zinc-700 text-sm">
            <input v-model="compatibleOnly" type="checkbox" /> Compatíveis com o agente
          </label>
          <button
            @click="refreshCatalog"
            :disabled="refreshingModels"
            class="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl font-semibold text-sm
                   bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black disabled:opacity-50"
          >
            <RefreshCw :size="16" :class="{ 'animate-spin': refreshingModels }" /> Atualizar
          </button>
        </div>

        <div class="text-sm text-gray-500">
          {{ filteredModels.length }} de {{ models.length }} modelos. Preços exibidos por 1 milhão de tokens.
        </div>

        <div class="grid gap-3">
          <div
            v-for="model in filteredModels"
            :key="model.id"
            class="rounded-2xl border bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800 overflow-hidden"
            :class="{ 'ring-2 ring-brand-yellow': model.id === selectedModel }"
          >
            <div class="p-4 flex flex-col lg:flex-row lg:items-center gap-4">
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <h3 class="font-bold truncate">{{ model.name }}</h3>
                  <span v-if="model.free" class="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">
                    grátis
                  </span>
                  <span v-if="model.toolCompatible" class="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">
                    tools
                  </span>
                  <span v-if="model.id === selectedModel" class="inline-flex items-center gap-1 text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-brand-yellow text-brand-black">
                    <Check :size="11" /> ativo
                  </span>
                </div>
                <code class="block text-xs text-gray-400 mt-1 break-all">{{ model.id }}</code>
              </div>
              <div class="grid grid-cols-3 gap-4 text-xs min-w-[310px]">
                <div>
                  <div class="text-gray-400">Entrada</div>
                  <div class="font-bold">{{ pricePerMillion(model.pricing.prompt) }}</div>
                </div>
                <div>
                  <div class="text-gray-400">Saída</div>
                  <div class="font-bold">{{ pricePerMillion(model.pricing.completion) }}</div>
                </div>
                <div>
                  <div class="text-gray-400">Contexto</div>
                  <div class="font-bold">{{ formatNumber(model.contextLength) }}</div>
                </div>
              </div>
              <div class="flex items-center gap-2">
                <button
                  @click="activateModel(model)"
                  :disabled="!model.toolCompatible || model.id === selectedModel || !!savingModel"
                  class="px-3 py-2 rounded-lg text-xs font-bold bg-indigo-600 text-white hover:bg-indigo-500 disabled:opacity-40"
                  :title="model.toolCompatible ? 'Usar no agente' : 'Este modelo não informa suporte a tools'"
                >
                  {{ savingModel === model.id ? "Salvando…" : model.id === selectedModel ? "Ativo" : "Usar" }}
                </button>
                <button
                  @click="expandedModel = expandedModel === model.id ? null : model.id"
                  class="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-zinc-800"
                  title="Detalhes"
                >
                  <ChevronDown :size="17" :class="{ 'rotate-180': expandedModel === model.id }" />
                </button>
              </div>
            </div>
            <div v-if="expandedModel === model.id" class="border-t border-gray-100 dark:border-zinc-800 p-4 space-y-3">
              <p class="text-sm text-gray-600 dark:text-gray-300">{{ model.description || "Sem descrição." }}</p>
              <div class="flex flex-wrap gap-2">
                <span v-for="parameter in model.supportedParameters" :key="parameter" class="text-[11px] px-2 py-1 rounded bg-gray-100 dark:bg-zinc-800">
                  {{ parameter }}
                </span>
              </div>
              <div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
                <div><span class="text-gray-400">Entradas:</span> {{ model.inputModalities.join(", ") || "—" }}</div>
                <div><span class="text-gray-400">Saídas:</span> {{ model.outputModalities.join(", ") || "—" }}</div>
                <div><span class="text-gray-400">Tokenizer:</span> {{ model.tokenizer || "—" }}</div>
                <div><span class="text-gray-400">Máx. saída:</span> {{ formatNumber(model.maxCompletionTokens || 0) || "—" }}</div>
              </div>
              <p v-if="!model.toolCompatible" class="text-xs text-amber-600 dark:text-amber-400">
                O modelo aparece para comparação, mas não pode ser ativado porque não declara suporte a tools e o agente depende delas.
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- Comandos -->
      <div v-else-if="tab === 'commands'" class="space-y-4">
        <div class="flex flex-col lg:flex-row gap-3">
          <div class="relative flex-1">
            <Search :size="17" class="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              v-model="commandSearch"
              @keyup.enter="loadCommands(true)"
              placeholder="Buscar comando, usuário ou modelo…"
              class="w-full pl-10 pr-3 py-2.5 rounded-xl border bg-white border-gray-200 dark:bg-zinc-900 dark:border-zinc-700"
            />
          </div>
          <select v-model="commandUser" class="px-3 py-2.5 rounded-xl border bg-white border-gray-200 dark:bg-zinc-900 dark:border-zinc-700">
            <option value="">Todos os colaboradores</option>
            <option v-for="user in overview?.usage.by_user || []" :key="user.app_user_id" :value="user.app_user_id">
              {{ user.display_name || user.username }}
            </option>
          </select>
          <select v-model="commandStatus" class="px-3 py-2.5 rounded-xl border bg-white border-gray-200 dark:bg-zinc-900 dark:border-zinc-700">
            <option value="">Todos os status</option>
            <option value="success">Sucesso</option>
            <option value="error">Erro</option>
          </select>
          <button @click="loadCommands(true)" class="px-4 py-2.5 rounded-xl text-sm font-semibold bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black">
            Filtrar
          </button>
        </div>

        <div class="rounded-2xl border bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800 overflow-hidden">
          <div v-if="commandsLoading" class="flex items-center justify-center gap-2 py-12 text-gray-500">
            <Loader2 :size="18" class="animate-spin" /> Carregando…
          </div>
          <div v-else-if="commands.length === 0" class="py-12 text-center text-sm text-gray-500">
            Nenhum comando encontrado.
          </div>
          <div v-else class="divide-y divide-gray-100 dark:divide-zinc-800">
            <div v-for="command in commands" :key="command.id">
              <button
                @click="expandedCommand = expandedCommand === command.id ? null : command.id"
                class="w-full p-4 text-left grid lg:grid-cols-[170px_1fr_150px_110px_36px] gap-3 items-center hover:bg-gray-50 dark:hover:bg-zinc-900/60"
              >
                <div>
                  <div class="text-sm font-semibold truncate">{{ command.display_name || command.username }}</div>
                  <div class="text-xs text-gray-400">{{ formatDate(command.created_at) }}</div>
                </div>
                <div class="min-w-0">
                  <div class="text-sm truncate">{{ command.command }}</div>
                  <code class="text-[11px] text-gray-400">{{ command.selected_model }}</code>
                </div>
                <div class="text-xs">
                  <div class="font-bold">{{ formatNumber(command.total_tokens) }} tokens</div>
                  <div class="text-gray-400">{{ command.request_count }} chamada(s) · {{ formatDuration(command.duration_ms) }}</div>
                </div>
                <div>
                  <div class="text-sm font-extrabold">{{ formatCost(command.cost) }}</div>
                  <span
                    class="text-[10px] font-bold uppercase"
                    :class="command.status === 'success' ? 'text-emerald-600' : 'text-red-500'"
                  >
                    {{ command.status === "success" ? "sucesso" : "erro" }}
                  </span>
                </div>
                <ChevronDown :size="17" :class="{ 'rotate-180': expandedCommand === command.id }" />
              </button>
              <div v-if="expandedCommand === command.id" class="px-4 pb-4">
                <div class="rounded-xl bg-gray-50 dark:bg-zinc-900 p-4 space-y-3 text-sm">
                  <div>
                    <div class="text-[10px] uppercase font-bold tracking-wider text-gray-400">Comando recebido</div>
                    <p class="whitespace-pre-wrap mt-1">{{ command.command }}</p>
                  </div>
                  <div v-if="command.reply">
                    <div class="text-[10px] uppercase font-bold tracking-wider text-gray-400">Resposta</div>
                    <p class="whitespace-pre-wrap mt-1 text-gray-600 dark:text-gray-300">{{ command.reply }}</p>
                  </div>
                  <div v-if="command.error_message" class="text-red-600">{{ command.error_message }}</div>
                  <div class="grid sm:grid-cols-3 gap-3 text-xs">
                    <div>Prompt: <strong>{{ formatNumber(command.prompt_tokens) }}</strong></div>
                    <div>Resposta: <strong>{{ formatNumber(command.completion_tokens) }}</strong></div>
                    <div>Modelos reais: <strong>{{ command.actual_models.join(", ") || command.selected_model }}</strong></div>
                  </div>
                  <div v-if="command.tool_events.length" class="flex flex-wrap gap-2">
                    <span v-for="event in command.tool_events" :key="event" class="text-[11px] px-2 py-1 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">
                      {{ event }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="flex items-center justify-between text-sm">
          <span class="text-gray-500">
            {{ commandTotal ? commandOffset + 1 : 0 }}–{{ Math.min(commandOffset + commandLimit, commandTotal) }} de {{ commandTotal }}
          </span>
          <div class="flex gap-2">
            <button @click="changeCommandPage(-1)" :disabled="commandOffset === 0" class="p-2 rounded-lg border disabled:opacity-40 dark:border-zinc-700">
              <ChevronLeft :size="17" />
            </button>
            <button @click="changeCommandPage(1)" :disabled="commandOffset + commandLimit >= commandTotal" class="p-2 rounded-lg border disabled:opacity-40 dark:border-zinc-700">
              <ChevronRight :size="17" />
            </button>
          </div>
        </div>
      </div>

      <!-- Memórias e skills -->
      <div v-else class="grid xl:grid-cols-2 gap-6">
        <section class="space-y-3">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="font-bold text-lg flex items-center gap-2"><Database :size="19" /> Memórias</h3>
              <p class="text-xs text-gray-500">Fatos permanentes incluídos no contexto do agente.</p>
            </div>
            <button @click="openMemory()" class="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-bold bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black">
              <Plus :size="15" /> Nova
            </button>
          </div>
          <div
            v-for="memory in memories"
            :key="memory.id"
            class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800"
          >
            <div class="flex items-start gap-3">
              <button @click="toggleMemory(memory)" class="mt-0.5" :title="memory.enabled ? 'Desativar' : 'Ativar'">
                <span class="flex w-9 h-5 rounded-full p-0.5 transition-colors" :class="memory.enabled ? 'bg-emerald-500 justify-end' : 'bg-gray-300 dark:bg-zinc-700 justify-start'">
                  <span class="w-4 h-4 bg-white rounded-full shadow" />
                </span>
              </button>
              <div class="min-w-0 flex-1">
                <h4 class="font-bold">{{ memory.title }}</h4>
                <p class="text-sm text-gray-500 dark:text-gray-400 mt-1 whitespace-pre-wrap">{{ memory.content }}</p>
              </div>
              <button @click="openMemory(memory)" class="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-zinc-800"><Pencil :size="16" /></button>
              <button @click="removeMemory(memory)" class="p-1.5 rounded text-red-500 hover:bg-red-50 dark:hover:bg-red-950"><Trash2 :size="16" /></button>
            </div>
          </div>
          <p v-if="memories.length === 0" class="text-sm text-gray-500 py-8 text-center">Nenhuma memória configurada.</p>
        </section>

        <section class="space-y-3">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="font-bold text-lg flex items-center gap-2"><BrainCircuit :size="19" /> Skills</h3>
              <p class="text-xs text-gray-500">Instruções especializadas; não alteram as permissões das tools.</p>
            </div>
            <button @click="openSkill()" class="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-bold bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black">
              <Plus :size="15" /> Nova
            </button>
          </div>
          <div
            v-for="skill in skills"
            :key="skill.id"
            class="rounded-2xl border p-4 bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800"
          >
            <div class="flex items-start gap-3">
              <button @click="toggleSkill(skill)" class="mt-0.5" :title="skill.enabled ? 'Desativar' : 'Ativar'">
                <span class="flex w-9 h-5 rounded-full p-0.5 transition-colors" :class="skill.enabled ? 'bg-indigo-500 justify-end' : 'bg-gray-300 dark:bg-zinc-700 justify-start'">
                  <span class="w-4 h-4 bg-white rounded-full shadow" />
                </span>
              </button>
              <div class="min-w-0 flex-1">
                <h4 class="font-bold">{{ skill.name }}</h4>
                <p v-if="skill.description" class="text-xs text-gray-400 mt-0.5">{{ skill.description }}</p>
                <p class="text-sm text-gray-500 dark:text-gray-400 mt-2 whitespace-pre-wrap">{{ skill.instructions }}</p>
              </div>
              <button @click="openSkill(skill)" class="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-zinc-800"><Pencil :size="16" /></button>
              <button @click="removeSkill(skill)" class="p-1.5 rounded text-red-500 hover:bg-red-50 dark:hover:bg-red-950"><Trash2 :size="16" /></button>
            </div>
          </div>
          <p v-if="skills.length === 0" class="text-sm text-gray-500 py-8 text-center">Nenhuma skill configurada.</p>
        </section>
      </div>
    </template>

    <Teleport to="body">
      <div v-if="editorOpen" class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50" @click.self="editorOpen = false">
        <div class="w-full max-w-2xl rounded-2xl shadow-xl bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700">
          <div class="flex items-center justify-between p-5 border-b border-gray-100 dark:border-zinc-800">
            <h3 class="font-bold text-lg">
              {{ editingId ? "Editar" : "Nova" }} {{ editorKind === "memory" ? "memória" : "skill" }}
            </h3>
            <button @click="editorOpen = false" class="p-1 rounded hover:bg-gray-100 dark:hover:bg-zinc-800"><X :size="20" /></button>
          </div>
          <div class="p-5 space-y-4">
            <template v-if="editorKind === 'memory'">
              <div>
                <label class="block text-xs font-bold text-gray-500 mb-1">Título</label>
                <input v-model="memoryForm.title" class="w-full px-3 py-2 rounded-lg border bg-gray-50 border-gray-200 dark:bg-zinc-900 dark:border-zinc-700" />
              </div>
              <div>
                <label class="block text-xs font-bold text-gray-500 mb-1">Conteúdo</label>
                <textarea v-model="memoryForm.content" rows="8" class="w-full px-3 py-2 rounded-lg border bg-gray-50 border-gray-200 dark:bg-zinc-900 dark:border-zinc-700 resize-y" />
              </div>
              <label class="inline-flex items-center gap-2 text-sm"><input v-model="memoryForm.enabled" type="checkbox" /> Ativa</label>
            </template>
            <template v-else>
              <div>
                <label class="block text-xs font-bold text-gray-500 mb-1">Nome</label>
                <input v-model="skillForm.name" class="w-full px-3 py-2 rounded-lg border bg-gray-50 border-gray-200 dark:bg-zinc-900 dark:border-zinc-700" />
              </div>
              <div>
                <label class="block text-xs font-bold text-gray-500 mb-1">Descrição curta</label>
                <input v-model="skillForm.description" class="w-full px-3 py-2 rounded-lg border bg-gray-50 border-gray-200 dark:bg-zinc-900 dark:border-zinc-700" />
              </div>
              <div>
                <label class="block text-xs font-bold text-gray-500 mb-1">Instruções da skill</label>
                <textarea v-model="skillForm.instructions" rows="8" class="w-full px-3 py-2 rounded-lg border bg-gray-50 border-gray-200 dark:bg-zinc-900 dark:border-zinc-700 resize-y" />
              </div>
              <label class="inline-flex items-center gap-2 text-sm"><input v-model="skillForm.enabled" type="checkbox" /> Ativa</label>
            </template>
            <p class="flex items-start gap-2 text-xs text-gray-500 bg-gray-50 dark:bg-zinc-900 rounded-lg p-3">
              <ShieldCheck :size="16" class="shrink-0 text-emerald-500" />
              Memórias e skills influenciam as respostas, mas não liberam ferramentas nem ignoram permissões dos colaboradores.
            </p>
            <p v-if="editorError" class="text-sm text-red-600">{{ editorError }}</p>
          </div>
          <div class="flex justify-end gap-2 px-5 pb-5">
            <button @click="editorOpen = false" class="px-4 py-2 rounded-lg border text-sm dark:border-zinc-700">Cancelar</button>
            <button @click="saveEditor" :disabled="editorSaving" class="px-4 py-2 rounded-lg text-sm font-bold bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black disabled:opacity-50">
              {{ editorSaving ? "Salvando…" : "Salvar" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

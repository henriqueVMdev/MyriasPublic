<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Loader2, Plus, Pencil, Trash2, X } from "lucide-vue-next";
import {
  listUsers,
  createUser,
  updateUser,
  deleteUser,
  getPermissionCatalog,
  type AppUser,
  type PermissionCatalog,
} from "@/api/users";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

// Rótulos amigáveis das chaves de permissão (espelha app/permissions.py).
const PERM_LABELS: Record<string, string> = {
  dashboard: "Dashboard",
  performance: "Performance",
  repetidos: "Anúncios Repetidos",
  quality: "Anúncios Incompletos",
  concorrencia: "Concorrência",
  bulk: "Edição por SKU",
  clone: "Copiar Anúncio",
  promocoes: "Promoções",
  logs: "Histórico",
  planilhas: "Planilhas",
  perguntas: "Perguntas",
  mensagens: "Mensagens",
  atendimento_historico: "Histórico de Atendimento",
  assistente: "Assistente de IA",
  delete_listing: "Excluir anúncio",
  bulk_edit: "Executar edição em massa",
  clone_listing: "Criar / copiar anúncio",
  manage_promotions: "Gerenciar promoções",
  reply_questions: "Responder perguntas",
  reply_messages: "Responder mensagens",
  manage_accounts: "Conectar / desconectar contas ML",
  dashboard_revenue: "Faturamento (Dashboard)",
};
const label = (key: string) => PERM_LABELS[key] || key;

const users = ref<AppUser[]>([]);
const catalog = ref<PermissionCatalog>({ sections: [], actions: [], metrics: [] });
const loading = ref(false);
const error = ref("");

// ---- Formulário (criar / editar) ----
const showForm = ref(false);
const editing = ref<AppUser | null>(null);
const form = ref({
  username: "",
  display_name: "",
  password: "",
  is_admin: false,
  is_active: true,
  permissions: new Set<string>(),
});
const saving = ref(false);
const formError = ref("");

const isEdit = computed(() => editing.value !== null);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [u, c] = await Promise.all([listUsers(), getPermissionCatalog()]);
    users.value = u;
    catalog.value = c;
  } catch (e: any) {
    error.value = e?.response?.data?.detail || "Erro ao carregar usuários.";
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openCreate() {
  editing.value = null;
  form.value = {
    username: "",
    display_name: "",
    password: "",
    is_admin: false,
    is_active: true,
    permissions: new Set<string>(),
  };
  formError.value = "";
  showForm.value = true;
}

function openEdit(u: AppUser) {
  editing.value = u;
  form.value = {
    username: u.username,
    display_name: u.display_name || "",
    password: "",
    is_admin: u.is_admin,
    is_active: u.is_active,
    permissions: new Set(u.permissions || []),
  };
  formError.value = "";
  showForm.value = true;
}

function togglePerm(key: string) {
  if (form.value.permissions.has(key)) form.value.permissions.delete(key);
  else form.value.permissions.add(key);
  // Força reatividade do Set
  form.value.permissions = new Set(form.value.permissions);
}

async function save() {
  formError.value = "";
  saving.value = true;
  try {
    const perms = Array.from(form.value.permissions);
    if (editing.value) {
      await updateUser(editing.value.id, {
        display_name: form.value.display_name || null,
        password: form.value.password || null,
        is_admin: form.value.is_admin,
        is_active: form.value.is_active,
        permissions: perms,
      });
    } else {
      await createUser({
        username: form.value.username.trim(),
        password: form.value.password,
        display_name: form.value.display_name || null,
        is_admin: form.value.is_admin,
        permissions: perms,
      });
    }
    showForm.value = false;
    await load();
  } catch (e: any) {
    formError.value = e?.response?.data?.detail || "Erro ao salvar.";
  } finally {
    saving.value = false;
  }
}

async function remove(u: AppUser) {
  if (!confirm(`Excluir o usuário "${u.username}"? Esta ação não pode ser desfeita.`)) return;
  try {
    await deleteUser(u.id);
    await load();
  } catch (e: any) {
    alert(e?.response?.data?.detail || "Erro ao excluir.");
  }
}
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div class="flex items-center justify-between gap-4">
      <div>
        <h2 class="text-2xl lg:text-3xl font-extrabold tracking-tight">Usuários</h2>
        <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
          Crie logins e defina as permissões de cada colaborador.
        </p>
      </div>
      <button
        @click="openCreate()"
        class="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold
               bg-brand-black text-brand-yellow hover:bg-brand-black-soft
               dark:bg-brand-yellow dark:text-brand-black dark:hover:bg-brand-yellow-dark"
      >
        <Plus :size="18" /> Novo usuário
      </button>
    </div>

    <p v-if="error" class="text-sm text-red-600 bg-red-50 dark:bg-red-900/30 rounded-lg px-3 py-2">
      {{ error }}
    </p>

    <div v-if="loading" class="flex items-center gap-2 text-gray-500">
      <Loader2 :size="18" class="animate-spin" /> Carregando…
    </div>

    <!-- Lista -->
    <div v-else class="grid gap-3">
      <div
        v-for="u in users"
        :key="u.id"
        class="rounded-2xl border p-4 flex items-center gap-4 transition-colors
               bg-white border-gray-200 dark:bg-brand-black-soft dark:border-zinc-800"
      >
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <span class="font-bold text-gray-900 dark:text-gray-100 truncate">
              {{ u.display_name || u.username }}
            </span>
            <span
              v-if="u.is_admin"
              class="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded
                     bg-brand-black text-brand-yellow dark:bg-brand-yellow dark:text-brand-black"
            >admin</span>
            <span
              v-if="!u.is_active"
              class="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded
                     bg-gray-200 text-gray-600 dark:bg-zinc-700 dark:text-gray-300"
            >inativo</span>
          </div>
          <div class="text-xs text-gray-500 dark:text-gray-400 mt-0.5">@{{ u.username }}</div>
          <div class="text-xs text-gray-400 dark:text-gray-500 mt-1">
            {{ u.is_admin ? "Acesso total" : `${u.permissions.length} permissõe(s)` }}
          </div>
        </div>
        <button
          @click="openEdit(u)"
          class="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-600 dark:text-gray-300"
          title="Editar"
        >
          <Pencil :size="18" />
        </button>
        <button
          v-if="u.id !== auth.me?.id"
          @click="remove(u)"
          class="p-2 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/30 text-red-500"
          title="Excluir"
        >
          <Trash2 :size="18" />
        </button>
      </div>
      <p v-if="users.length === 0" class="text-sm text-gray-500">Nenhum usuário cadastrado.</p>
    </div>

    <!-- Modal de criar/editar -->
    <Teleport to="body">
      <div
        v-if="showForm"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50 overflow-y-auto"
        @click.self="showForm = false"
      >
        <div
          class="w-full max-w-2xl my-8 rounded-2xl shadow-xl
                 bg-white dark:bg-brand-black-soft border border-gray-200 dark:border-zinc-700"
        >
          <div class="flex items-center justify-between p-5 border-b border-gray-100 dark:border-zinc-800">
            <h3 class="text-lg font-bold text-gray-900 dark:text-gray-100">
              {{ isEdit ? `Editar ${editing?.username}` : "Novo usuário" }}
            </h3>
            <button @click="showForm = false" class="p-1 rounded-lg hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-400">
              <X :size="20" />
            </button>
          </div>

          <div class="p-5 space-y-4">
            <div class="grid sm:grid-cols-2 gap-4">
              <div>
                <label class="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">Usuário (login)</label>
                <input
                  v-model="form.username"
                  :disabled="isEdit"
                  placeholder="ex: joao"
                  class="w-full px-3 py-2 rounded-lg text-sm border border-gray-200 bg-gray-50 text-gray-900
                         disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-gray-100"
                />
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">Nome de exibição</label>
                <input
                  v-model="form.display_name"
                  placeholder="ex: João Silva"
                  class="w-full px-3 py-2 rounded-lg text-sm border border-gray-200 bg-gray-50 text-gray-900
                         dark:border-zinc-700 dark:bg-zinc-900 dark:text-gray-100"
                />
              </div>
            </div>

            <div>
              <label class="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">
                {{ isEdit ? "Nova senha (deixe em branco para manter)" : "Senha" }}
              </label>
              <input
                v-model="form.password"
                type="password"
                autocomplete="new-password"
                placeholder="••••••••"
                class="w-full px-3 py-2 rounded-lg text-sm border border-gray-200 bg-gray-50 text-gray-900
                       dark:border-zinc-700 dark:bg-zinc-900 dark:text-gray-100"
              />
            </div>

            <div class="flex flex-wrap gap-4">
              <label class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                <input type="checkbox" v-model="form.is_admin" class="accent-brand-black dark:accent-brand-yellow" />
                Administrador (acesso total + gerencia usuários)
              </label>
              <label v-if="isEdit" class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                <input type="checkbox" v-model="form.is_active" class="accent-brand-black dark:accent-brand-yellow" />
                Ativo
              </label>
            </div>

            <!-- Permissões -->
            <div v-if="!form.is_admin" class="space-y-4 pt-2">
              <div>
                <h4 class="text-xs font-bold uppercase tracking-wide text-gray-500 mb-2">Seções</h4>
                <div class="grid sm:grid-cols-2 gap-2">
                  <label
                    v-for="key in catalog.sections"
                    :key="key"
                    class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 px-2 py-1 rounded-lg
                           hover:bg-gray-50 dark:hover:bg-zinc-800 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      :checked="form.permissions.has(key)"
                      @change="togglePerm(key)"
                      class="accent-brand-black dark:accent-brand-yellow"
                    />
                    {{ label(key) }}
                  </label>
                </div>
              </div>
              <div>
                <h4 class="text-xs font-bold uppercase tracking-wide text-gray-500 mb-2">Ações destrutivas</h4>
                <div class="grid sm:grid-cols-2 gap-2">
                  <label
                    v-for="key in catalog.actions"
                    :key="key"
                    class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 px-2 py-1 rounded-lg
                           hover:bg-gray-50 dark:hover:bg-zinc-800 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      :checked="form.permissions.has(key)"
                      @change="togglePerm(key)"
                      class="accent-red-600"
                    />
                    {{ label(key) }}
                  </label>
                </div>
              </div>
              <div v-if="catalog.metrics.length">
                <h4 class="text-xs font-bold uppercase tracking-wide text-gray-500 mb-2">Métricas / Gráficos</h4>
                <div class="grid sm:grid-cols-2 gap-2">
                  <label
                    v-for="key in catalog.metrics"
                    :key="key"
                    class="inline-flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 px-2 py-1 rounded-lg
                           hover:bg-gray-50 dark:hover:bg-zinc-800 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      :checked="form.permissions.has(key)"
                      @change="togglePerm(key)"
                      class="accent-brand-black dark:accent-brand-yellow"
                    />
                    {{ label(key) }}
                  </label>
                </div>
              </div>
            </div>
            <p v-else class="text-sm text-gray-500 dark:text-gray-400">
              Administradores têm acesso a tudo — não é preciso marcar permissões.
            </p>

            <p v-if="formError" class="text-sm text-red-600 bg-red-50 dark:bg-red-900/30 rounded-lg px-3 py-2">
              {{ formError }}
            </p>
          </div>

          <div class="flex justify-end gap-2 p-5 pt-0">
            <button
              @click="showForm = false"
              :disabled="saving"
              class="px-4 py-2 rounded-lg text-sm border hover:bg-gray-50 disabled:opacity-50
                     dark:border-zinc-700 dark:hover:bg-zinc-800 dark:text-gray-200"
            >
              Cancelar
            </button>
            <button
              @click="save()"
              :disabled="saving || !form.username || (!isEdit && !form.password)"
              class="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold
                     bg-brand-black text-brand-yellow hover:bg-brand-black-soft disabled:opacity-50
                     dark:bg-brand-yellow dark:text-brand-black dark:hover:bg-brand-yellow-dark"
            >
              <Loader2 v-if="saving" :size="16" class="animate-spin" />
              {{ isEdit ? "Salvar" : "Criar usuário" }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

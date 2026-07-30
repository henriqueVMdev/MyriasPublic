import api from "./client";

// Usuário humano do painel (login fixo criado pelo admin).
export interface AppUser {
  id: number;
  username: string;
  display_name: string | null;
  is_admin: boolean;
  is_active: boolean;
  permissions: string[];
}

// Versão pública (dropdown da tela de login).
export interface PublicUser {
  username: string;
  display_name: string | null;
}

export interface SessionInfo {
  authenticated: boolean;
  password_required?: boolean;
  /** Deploy público de demonstração: escrita bloqueada no backend. */
  demo_mode?: boolean;
  user: {
    id: number;
    username: string;
    display_name: string | null;
    is_admin: boolean;
    permissions: string[];
  } | null;
}

export interface PermissionCatalog {
  sections: string[];
  actions: string[];
  metrics: string[];
}

// ---- Sessão / login (rotas públicas em /app) ----

export async function getSession(): Promise<SessionInfo> {
  const { data } = await api.get<SessionInfo>("/app/session");
  return data;
}

export async function getLoginUsers(): Promise<PublicUser[]> {
  const { data } = await api.get<PublicUser[]>("/app/users");
  return data;
}

export async function appLogin(username: string, password: string): Promise<void> {
  await api.post("/app/login", { username, password });
}

export async function appLogout(): Promise<void> {
  await api.post("/app/logout");
}

// ---- CRUD (admin) ----

export interface UserCreatePayload {
  username: string;
  password: string;
  display_name?: string | null;
  is_admin?: boolean;
  permissions?: string[];
}

export interface UserUpdatePayload {
  display_name?: string | null;
  password?: string | null;
  is_admin?: boolean;
  is_active?: boolean;
  permissions?: string[];
}

export async function listUsers(): Promise<AppUser[]> {
  const { data } = await api.get<AppUser[]>("/users");
  return data;
}

export async function getPermissionCatalog(): Promise<PermissionCatalog> {
  const { data } = await api.get<PermissionCatalog>("/users/permissions");
  return data;
}

export async function createUser(payload: UserCreatePayload): Promise<AppUser> {
  const { data } = await api.post<AppUser>("/users", payload);
  return data;
}

export async function updateUser(id: number, payload: UserUpdatePayload): Promise<AppUser> {
  const { data } = await api.put<AppUser>(`/users/${id}`, payload);
  return data;
}

export async function deleteUser(id: number): Promise<void> {
  await api.delete(`/users/${id}`);
}

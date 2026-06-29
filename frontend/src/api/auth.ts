import api from "./client";

export interface AuthStatus {
  authenticated: boolean;
  user_id?: number;
  nickname?: string;
  expires_at?: string;
  expires_in_seconds?: number;
}

export interface MeliAccount {
  user_id: number;
  nickname: string;
  is_active: boolean;
  expires_at: string;
  expired: boolean;
}

export async function getAuthStatus(): Promise<AuthStatus> {
  const { data } = await api.get<AuthStatus>("/auth/status");
  return data;
}

export async function getAccounts(): Promise<MeliAccount[]> {
  const { data } = await api.get<{ accounts: MeliAccount[] }>("/auth/accounts");
  return data.accounts;
}

export async function switchAccount(userId: number): Promise<AuthStatus> {
  const { data } = await api.post<AuthStatus>("/auth/accounts/switch", { user_id: userId });
  return data;
}

export async function removeAccount(userId: number): Promise<void> {
  await api.post("/auth/accounts/remove", { user_id: userId });
}

export function getLoginUrl(): string {
  return "/api/auth/login";
}

export async function logout(): Promise<void> {
  await api.post("/auth/logout");
}

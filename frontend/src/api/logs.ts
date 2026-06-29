import api from "./client";

export interface OperationLog {
  id: number;
  operation_type: string;
  item_ids: string[] | null;
  payload: Record<string, unknown> | null;
  response?: Record<string, unknown> | null;
  status: string;
  error_message: string | null;
  batch_id?: string | null;
  actor?: string | null;
  user_id?: number | null;
  created_at: string | null;
}

// Operação centrada no usuário: agrupa as linhas-filho de uma mesma publicação.
export interface OperationGroup {
  key: string;
  operation_type: string;
  actor?: string | null;
  status: "success" | "error" | "partial";
  total: number;
  success: number;
  error: number;
  partial: number;
  created_at: string | null;
  children: OperationLog[];
}

export interface OperationsResponse {
  operations: OperationGroup[];
  paging: { total: number; offset: number; limit: number };
}

export interface OperationFilters {
  operation_type?: string;
  status?: string;
  actor?: string;
  date_from?: string;
  date_to?: string;
  offset?: number;
  limit?: number;
}

export async function getActors(): Promise<string[]> {
  const { data } = await api.get<{ actors: string[] }>("/logs/actors");
  return data.actors;
}

export async function getOperations(
  filters: OperationFilters = {}
): Promise<OperationsResponse> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<OperationsResponse>("/logs/operations", { params });
  return data;
}

export async function getOperationDetail(
  key: string
): Promise<{ key: string; rows: OperationLog[] }> {
  const { data } = await api.get<{ key: string; rows: OperationLog[] }>(
    `/logs/operations/${encodeURIComponent(key)}`
  );
  return data;
}

export async function getAtendimentoLogs(
  filters: { operation_type?: string; actor?: string; offset?: number; limit?: number } = {}
): Promise<LogsResponse> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<LogsResponse>("/logs/atendimento", { params });
  return data;
}

export interface LogsResponse {
  logs: OperationLog[];
  paging: { total: number; offset: number; limit: number };
}

export interface LogStats {
  total: number;
  by_type: Record<string, number>;
  by_status: Record<string, number>;
  recent: Array<{
    id: number;
    operation_type: string;
    item_ids: string[] | null;
    status: string;
    created_at: string | null;
    payload?: Record<string, unknown> | null;
  }>;
}

export interface LogFilters {
  operation_type?: string;
  status?: string;
  item_id?: string;
  offset?: number;
  limit?: number;
}

export async function getLogs(filters: LogFilters = {}): Promise<LogsResponse> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, v]) => v !== undefined && v !== "")
  );
  const { data } = await api.get<LogsResponse>("/logs", { params });
  return data;
}

export async function getLogStats(): Promise<LogStats> {
  const { data } = await api.get<LogStats>("/logs/stats");
  return data;
}

export interface ItemLogGroup {
  item_id: string;
  op_count: number;
  last_at: string | null;
  error_count: number;
  success_count: number;
  partial_count: number;
  op_types: string[];
}

export interface ItemLogGroupsResponse {
  items: ItemLogGroup[];
  paging: { total: number; offset: number; limit: number };
}

export async function getLogsByItem(
  offset = 0,
  limit = 50
): Promise<ItemLogGroupsResponse> {
  const { data } = await api.get<ItemLogGroupsResponse>("/logs/by-item", {
    params: { offset, limit },
  });
  return data;
}

export async function getLogsForItem(itemId: string): Promise<OperationLog[]> {
  const { data } = await api.get<LogsResponse>("/logs", {
    params: { item_id: itemId, limit: 100, offset: 0 },
  });
  return data.logs;
}

import api from "./client";

export interface QualityIssue {
  key: string;
  label: string;
  type: "attribute" | "description" | "clip" | "picture" | "other";
  attribute_id?: string;
}

export interface QualityItem {
  id: string;
  title: string;
  sku: string;
  thumbnail?: string | null;
  permalink?: string | null;
  status: string;
  score?: number | null;
  level?: string | null;
  validation_status?: "validating" | "confirmed";
  issues: QualityIssue[];
}

export interface IssueSummary extends QualityIssue {
  count: number;
}

export interface QualityResponse {
  has_snapshot: boolean;
  refreshing: boolean;
  status: "empty" | "running" | "complete" | "error" | "interrupted";
  started_at?: string | null;
  scanned_at?: string | null;
  processed: number;
  total: number;
  validating_count: number;
  summary: {
    analyzed: number;
    incomplete: number;
    complete: number;
    attributes: number;
    description: number;
    clip: number;
    pictures: number;
    issues: IssueSummary[];
  };
  items: QualityItem[];
  paging: { total: number; offset: number; limit: number };
  warnings: string[];
}

export interface QualityFilters {
  q?: string;
  issue?: string;
  status?: string;
  offset?: number;
  limit?: number;
}

export async function getQualityReport(filters: QualityFilters = {}): Promise<QualityResponse> {
  const params = Object.fromEntries(
    Object.entries(filters).filter(([, value]) => value !== undefined && value !== "")
  );
  const { data } = await api.get<QualityResponse>("/quality", { params });
  return data;
}

export async function refreshQuality(): Promise<{ started: boolean; already_running: boolean }> {
  const { data } = await api.post("/quality/refresh");
  return data;
}

export async function refreshQualityItem(itemId: string): Promise<void> {
  await api.post(`/quality/items/${encodeURIComponent(itemId)}/refresh`);
}

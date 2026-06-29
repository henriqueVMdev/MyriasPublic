import api from "./client";

export interface DashboardSummary {
  authenticated: boolean;
  user_ids?: number[];
  counts?: {
    active: number;
    paused: number;
    closed: number;
    pending: number;
    total: number;
  };
  error?: string;
}

export async function getDashboardSummary(
  accounts: string = "all"
): Promise<DashboardSummary> {
  const { data } = await api.get<DashboardSummary>("/dashboard/summary", {
    params: { accounts },
  });
  return data;
}

export interface RevenueAccount {
  user_id: number;
  nickname: string;
  series: number[];
  total: number;
  total_orders: number;
  avg_ticket: number;
  avg_orders_per_day: number;
  avg_revenue_per_day: number;
}

export interface DashboardRevenue {
  days: number;
  from?: string;
  to?: string;
  dates: string[];
  accounts: RevenueAccount[];
  combined: number[];
  combined_total: number;
  combined_total_orders: number;
  combined_avg_ticket: number;
  combined_avg_orders_per_day: number;
  combined_avg_revenue_per_day: number;
}

export interface RevenueParams {
  days?: number;
  from?: string;  // YYYY-MM-DD
  to?: string;    // YYYY-MM-DD
}

export async function getDashboardRevenue(
  params: number | RevenueParams = 30
): Promise<DashboardRevenue> {
  // Backwards compat: aceita number direto (= days)
  const p: RevenueParams = typeof params === "number" ? { days: params } : params;
  const { data } = await api.get<DashboardRevenue>("/dashboard/revenue", {
    params: p,
  });
  return data;
}

export interface ReputationMetric {
  rate: number;
  value: number;
  period: string;
  limit: number;
  within_limit: boolean;
}

export interface ReputationAccount {
  user_id: number;
  nickname: string;
  level_id: string | null;
  power_seller_status: string | null;
  sales_completed: number;
  metrics: {
    claims: ReputationMetric;
    mediations: ReputationMetric;
    cancellations: ReputationMetric;
    delayed_handling: ReputationMetric;
  };
  error?: string;
}

export interface ReputationResponse {
  accounts: ReputationAccount[];
}

export async function getDashboardReputation(): Promise<ReputationResponse> {
  const { data } = await api.get<ReputationResponse>("/dashboard/reputation");
  return data;
}

export interface SalesItem {
  sku: string;
  item_id: string;
  title: string;
  quantity: number;
  revenue: number;
  account: { user_id: number; nickname: string };
}

export interface SalesResponse {
  days: number;
  start: string;
  end: string;
  items: SalesItem[];
  total_quantity: number;
  total_revenue: number;
  accounts: Array<{ user_id: number; nickname: string }>;
}

export async function getDashboardSales(days: number = 1): Promise<SalesResponse> {
  const { data } = await api.get<SalesResponse>("/dashboard/sales", {
    params: { days },
  });
  return data;
}

export interface PerformanceAccount {
  user_id: number;
  nickname: string;
  visits_series: number[];
  sales_series: number[];
  questions_series: number[];
  total_visits: number;
  total_sales: number;
  total_questions: number;
  sales_per_visit: number;
  questions_per_visit: number;
  sales_per_question: number;
}

export interface PerformanceCombined {
  visits_series: number[];
  sales_series: number[];
  questions_series: number[];
  total_visits: number;
  total_sales: number;
  total_questions: number;
  sales_per_visit: number;
  questions_per_visit: number;
  sales_per_question: number;
}

export interface PerformanceData {
  days: number;
  from: string;
  to: string;
  dates: string[];
  accounts: PerformanceAccount[];
  combined: PerformanceCombined;
}

export async function getDashboardPerformance(
  params: number | RevenueParams = 30
): Promise<PerformanceData> {
  const p: RevenueParams = typeof params === "number" ? { days: params } : params;
  const { data } = await api.get<PerformanceData>("/dashboard/performance", { params: p });
  return data;
}

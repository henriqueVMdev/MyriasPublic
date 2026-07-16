import api from "./client";

export interface AiModelInfo {
  id: string;
  name: string;
  description: string;
  created: number;
  contextLength: number;
  pricing: Record<string, string>;
  supportedParameters: string[];
  inputModalities: string[];
  outputModalities: string[];
  tokenizer: string;
  instructType: string;
  maxCompletionTokens: number | null;
  knowledgeCutoff: string;
  defaultParameters: Record<string, unknown> | null;
  free: boolean;
  toolCompatible: boolean;
}

export interface AiToolInfo {
  name: string;
  description: string;
  write: boolean;
  permission: string | null;
  parameters: Record<string, unknown>;
}

export interface AiUserUsage {
  app_user_id: number;
  username: string;
  display_name: string | null;
  commands: number;
  tokens: number;
  cost: number;
}

export interface AiUsageStats {
  total_commands: number;
  successful_commands: number;
  total_tokens: number;
  total_cost: number;
  by_user: AiUserUsage[];
}

export interface AiOverview {
  agent: {
    name: string;
    description: string;
    selected_model: string;
    max_iterations: number;
  };
  catalog_count: number;
  memories_count: number;
  skills_count: number;
  tools: AiToolInfo[];
  usage: AiUsageStats;
}

export interface AiCommand {
  id: number;
  app_user_id: number;
  username: string;
  display_name: string | null;
  command: string;
  reply: string | null;
  selected_model: string;
  actual_models: string[];
  tool_events: string[];
  generation_ids: string[];
  status: "success" | "error";
  error_message: string | null;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  cost: number;
  request_count: number;
  duration_ms: number;
  created_at: string;
}

export interface AiMemory {
  id: number;
  title: string;
  content: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AiSkill {
  id: number;
  name: string;
  description: string;
  instructions: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export async function getAiOverview(): Promise<AiOverview> {
  const { data } = await api.get<AiOverview>("/ai/admin/overview");
  return data;
}

export async function getAiModels(): Promise<{ models: AiModelInfo[]; selected: string }> {
  const { data } = await api.get<{ models: AiModelInfo[]; selected: string }>("/ai/admin/models");
  return data;
}

export async function refreshAiModels(): Promise<{ models: AiModelInfo[]; selected: string }> {
  const { data } = await api.post<{ models: AiModelInfo[]; selected: string }>("/ai/admin/models/refresh");
  return data;
}

export async function setAiModel(model: string): Promise<{ selected: string }> {
  const { data } = await api.put<{ selected: string }>("/ai/admin/model", { model });
  return data;
}

export async function getAiCommands(params: {
  userId?: number;
  status?: string;
  q?: string;
  offset?: number;
  limit?: number;
}): Promise<{ commands: AiCommand[]; paging: { total: number; offset: number; limit: number } }> {
  const { data } = await api.get("/ai/admin/commands", { params });
  return data;
}

export async function getAiMemories(): Promise<AiMemory[]> {
  const { data } = await api.get<{ memories: AiMemory[] }>("/ai/admin/memories");
  return data.memories;
}

export async function createAiMemory(input: {
  title: string;
  content: string;
  enabled: boolean;
}): Promise<AiMemory> {
  const { data } = await api.post<AiMemory>("/ai/admin/memories", input);
  return data;
}

export async function updateAiMemory(id: number, input: {
  title: string;
  content: string;
  enabled: boolean;
}): Promise<AiMemory> {
  const { data } = await api.put<AiMemory>(`/ai/admin/memories/${id}`, input);
  return data;
}

export async function deleteAiMemory(id: number): Promise<void> {
  await api.delete(`/ai/admin/memories/${id}`);
}

export async function getAiSkills(): Promise<AiSkill[]> {
  const { data } = await api.get<{ skills: AiSkill[] }>("/ai/admin/skills");
  return data.skills;
}

export async function createAiSkill(input: {
  name: string;
  description: string;
  instructions: string;
  enabled: boolean;
}): Promise<AiSkill> {
  const { data } = await api.post<AiSkill>("/ai/admin/skills", input);
  return data;
}

export async function updateAiSkill(id: number, input: {
  name: string;
  description: string;
  instructions: string;
  enabled: boolean;
}): Promise<AiSkill> {
  const { data } = await api.put<AiSkill>(`/ai/admin/skills/${id}`, input);
  return data;
}

export async function deleteAiSkill(id: number): Promise<void> {
  await api.delete(`/ai/admin/skills/${id}`);
}

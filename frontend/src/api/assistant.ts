import api from "./client";

export interface AssistantMessage {
  role: "user" | "assistant";
  content: string;
}

export interface PendingAction {
  id: string;
  tool: string;
  summary: string;
  args: unknown;
}

export interface ChatResponse {
  reply: string;
  tool_events: string[];
  pending_action?: PendingAction | null;
  error?: boolean;
}

export interface ConfirmResponse {
  ok: boolean;
  summary: string;
  result: Record<string, unknown>;
}

// Timeout longo: o loop pode encadear várias consultas ao ML + o modelo.
export async function sendChat(messages: AssistantMessage[]): Promise<ChatResponse> {
  const { data } = await api.post<ChatResponse>(
    "/ai/chat",
    { messages },
    { timeout: 300_000 }
  );
  return data;
}

export async function confirmAction(id: string): Promise<ConfirmResponse> {
  const { data } = await api.post<ConfirmResponse>(`/ai/actions/${id}/confirm`, {}, { timeout: 300_000 });
  return data;
}

export async function rejectAction(id: string): Promise<void> {
  await api.post(`/ai/actions/${id}/reject`);
}

export async function listModels(): Promise<{ models: string[]; selected: string }> {
  const { data } = await api.get<{ models: string[]; selected: string }>("/ai/models");
  return data;
}

export async function updateModel(model: string): Promise<{ selected: string }> {
  const { data } = await api.put<{ selected: string }>("/ai/model", { model });
  return data;
}

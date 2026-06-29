import api from "./client";

export interface ScriptLogEntry {
  filename: string;
  script_key: string;
  label: string;
  datetime: string;
  size_bytes: number;
  row_count: number;
}

export async function listScriptLogs(): Promise<ScriptLogEntry[]> {
  const { data } = await api.get<{ logs: ScriptLogEntry[] }>("/script-logs");
  return data.logs;
}

export async function deleteScriptLog(filename: string): Promise<void> {
  await api.delete(`/script-logs/${encodeURIComponent(filename)}`);
}

export function downloadScriptLogUrl(filename: string): string {
  return `/api/script-logs/${encodeURIComponent(filename)}/download`;
}

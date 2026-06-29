import api from "./client";

export interface QuestionItem {
  id: string | null;
  title: string | null;
  thumbnail: string | null;
  permalink: string | null;
  sku: string | null;
}

export interface QuestionBuyer {
  id: number | null;
  nickname: string | null;
}

export interface QuestionAccount {
  user_id: number;
  nickname: string;
}

export interface QuestionAnswer {
  text?: string | null;
  status?: string | null;
  date_created?: string | null;
}

export interface QuestionHistory {
  id: number;
  text: string;
  date_created: string | null;
  answer: QuestionAnswer | null;
}

export interface Question {
  id: number;
  text: string;
  status: string;
  date_created: string | null;
  answer: QuestionAnswer | null;
  deleted_from_listing: boolean;
  hold: boolean;
  item: QuestionItem;
  buyer: QuestionBuyer;
  account: QuestionAccount;
  history: QuestionHistory[];
}

export interface QuestionsResponse {
  questions: Question[];
  counts: Record<string, number>;
  accounts: QuestionAccount[];
}

export async function listQuestions(
  status: "UNANSWERED" | "ANSWERED" | "CLOSED" = "UNANSWERED"
): Promise<QuestionsResponse> {
  const { data } = await api.get<QuestionsResponse>("/questions", {
    params: { status },
  });
  return data;
}

export interface QuestionStatsAccount {
  user_id: number;
  nickname: string;
  series: Array<{ label: string; count: number }>;
  total: number;
}

export interface QuestionStats {
  period: "day" | "month";
  periods: number;
  accounts: QuestionStatsAccount[];
}

export async function getQuestionStats(
  period: "day" | "month" = "day",
  periods: number = 30
): Promise<QuestionStats> {
  const { data } = await api.get<QuestionStats>("/questions/stats", {
    params: { period, periods },
  });
  return data;
}

export async function answerQuestion(
  questionId: number,
  text: string,
  accountUserId: number,
  context?: { itemId?: string | null; questionText?: string | null }
): Promise<unknown> {
  const { data } = await api.post(`/questions/${questionId}/answer`, {
    text,
    account_user_id: accountUserId,
    item_id: context?.itemId ?? undefined,
    question_text: context?.questionText ?? undefined,
  });
  return data;
}

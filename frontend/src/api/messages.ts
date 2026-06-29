import api from "./client";

export interface MessageAccount {
  user_id: number;
  nickname: string;
}

export interface ConversationItem {
  item_id: string | null;
  title: string;
  sku: string;
  quantity: number;
  thumbnail: string | null;
  permalink: string | null;
}

export interface Conversation {
  pack_id: string;
  buyer_id: number | null;
  buyer_nickname: string | null;
  last_text: string;
  last_date: string | null;
  unread_count: number;
  items: ConversationItem[];
  account: MessageAccount;
}

export interface UnreadResponse {
  conversations: Conversation[];
  counts: Record<string, number>;
  accounts: MessageAccount[];
}

export interface ThreadMessage {
  id: string;
  text: string;
  date: string | null;
  from_user_id: number | null;
  to_user_id: number | null;
  is_seller: boolean;
  status: string | null;
}

export interface Thread {
  pack_id: string;
  seller_user_id: number;
  buyer_id: number | null;
  messages: ThreadMessage[];
  error?: string;
}

export async function listUnread(enrich = false): Promise<UnreadResponse> {
  const { data } = await api.get<UnreadResponse>("/messages/unread", {
    params: enrich ? { enrich: 1 } : {},
  });
  return data;
}

export async function getThread(
  packId: string,
  accountUserId: number
): Promise<Thread> {
  const { data } = await api.get<Thread>(`/messages/packs/${packId}`, {
    params: { account_user_id: accountUserId },
  });
  return data;
}

export async function sendReply(
  packId: string,
  accountUserId: number,
  buyerUserId: number,
  text: string
): Promise<unknown> {
  const { data } = await api.post(`/messages/packs/${packId}/reply`, {
    text,
    account_user_id: accountUserId,
    buyer_user_id: buyerUserId,
  });
  return data;
}

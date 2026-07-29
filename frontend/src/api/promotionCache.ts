import type { PromotionItem } from "./promotions";

// Cache de sessão da varredura de uma promoção: guarda o estado de trabalho por
// (conta, promoção, tipo, aba) pra não re-varrer tudo ao sair e reentrar.
export interface ScanCacheEntry {
  items: PromotionItem[];
  total: number | null;
  active: string[]; // categorias marcadas
  enriched: string[]; // ids já enriquecidos (LIGHTNING) — evita rebuscar limites
  prices: Record<string, number | null>; // preços promocionais digitados
  stocks: Record<string, number | null>; // estoque reservado (LIGHTNING)
}

// ponytail: cache em memória, sem TTL. Some no reload da página e no botão "atualizar";
// invalidado nas escritas (incluir/remover) pra não mostrar elegibilidade velha.
const cache = new Map<string, ScanCacheEntry>();

function key(account: number | undefined, promotionId: string, promotionType: string, tab: string): string {
  return `${account ?? "?"}::${promotionId}::${promotionType}::${tab}`;
}

export function getScanCache(
  account: number | undefined, promotionId: string, promotionType: string, tab: string
): ScanCacheEntry | undefined {
  return cache.get(key(account, promotionId, promotionType, tab));
}

export function setScanCache(
  account: number | undefined, promotionId: string, promotionType: string, tab: string, entry: ScanCacheEntry
): void {
  cache.set(key(account, promotionId, promotionType, tab), entry);
}

// Limpa TODAS as abas de uma promoção (usado após incluir/remover: itens migram entre
// elegíveis/participando, então as duas ficam velhas).
export function clearScanCache(account: number | undefined, promotionId: string, promotionType: string): void {
  const prefix = `${account ?? "?"}::${promotionId}::${promotionType}::`;
  for (const k of [...cache.keys()]) if (k.startsWith(prefix)) cache.delete(k);
}

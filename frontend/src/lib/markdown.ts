import { marked } from "marked";

// Links do modelo: só http(s), mailto, âncora ou caminho — derruba javascript:/data: etc.
const SAFE_HREF = /^(https?:|mailto:|#|\/)/i;

// Remove caracteres de controle antes de validar: "java\tscript:" não passa.
const CONTROL_CHARS = new RegExp("[\\u0000-\\u0020]", "g");

marked.use({
  walkTokens(token) {
    if (
      (token.type === "link" || token.type === "image") &&
      !SAFE_HREF.test(token.href.replace(CONTROL_CHARS, ""))
    ) {
      token.href = "#";
    }
  },
});

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/**
 * Markdown seguro para resposta de modelo: escapa o HTML ANTES do marked,
 * então nada que o modelo escreva vira tag. Usado pelo widget e pela página
 * de chat — mantenha só esta cópia, é código sensível.
 */
export function renderMarkdown(s: string): string {
  return marked.parse(escapeHtml(s), { async: false, breaks: true }) as string;
}

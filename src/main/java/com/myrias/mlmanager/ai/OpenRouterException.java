package com.myrias.mlmanager.ai;

/** Erro amigável do OpenRouter — a mensagem é exibida direto no chat. */
public class OpenRouterException extends RuntimeException {
    public OpenRouterException(String message) { super(message); }
}

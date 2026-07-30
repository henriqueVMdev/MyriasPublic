package com.hrb.mlmanager.ops;

/**
 * Ator (usuário humano) da requisição em curso, espelho do {@code ContextVar}
 * de backend/app/models/operation_log.py. Em Spring MVC cada request roda numa
 * thread, então um {@link ThreadLocal} cumpre o mesmo papel: a autenticação
 * grava o nome aqui e o {@link OperationLog} lê na hora de persistir, sem
 * propagar o ator por dezenas de assinaturas de serviço.
 *
 * ponytail: as threads são reusadas de um pool — o {@link #clear()} no fim do
 * request (AppAuthFilter) é obrigatório para não vazar o ator pro próximo.
 */
public final class CurrentActor {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private CurrentActor() {}

    public static void set(String name) {
        ACTOR.set(name);
    }

    public static String get() {
        return ACTOR.get();
    }

    public static void clear() {
        ACTOR.remove();
    }
}

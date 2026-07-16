package com.hrb.mlmanager.ai;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD e montagem do contexto persistente do agente. */
@Service
public class AiCustomizationService {

    private static final int MAX_NAME = 160;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_CONTENT = 12_000;
    private static final int MAX_PROMPT_CONTEXT = 30_000;

    private final AiMemoryRepository memories;
    private final AiSkillRepository skills;

    public AiCustomizationService(AiMemoryRepository memories, AiSkillRepository skills) {
        this.memories = memories;
        this.skills = skills;
    }

    @Transactional(readOnly = true)
    public List<AiMemory> listMemories() {
        return memories.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public AiMemory createMemory(String title, String content, boolean enabled) {
        return memories.save(new AiMemory(required(title, "Título", MAX_NAME),
                required(content, "Conteúdo", MAX_CONTENT), enabled));
    }

    @Transactional
    public AiMemory updateMemory(long id, String title, String content, boolean enabled) {
        AiMemory memory = memories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Memória não encontrada."));
        memory.setTitle(required(title, "Título", MAX_NAME));
        memory.setContent(required(content, "Conteúdo", MAX_CONTENT));
        memory.setEnabled(enabled);
        return memories.save(memory);
    }

    @Transactional
    public void deleteMemory(long id) {
        if (!memories.existsById(id)) throw new IllegalArgumentException("Memória não encontrada.");
        memories.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<AiSkill> listSkills() {
        return skills.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public AiSkill createSkill(String name, String description, String instructions, boolean enabled) {
        return skills.save(new AiSkill(required(name, "Nome", MAX_NAME),
                optional(description, MAX_DESCRIPTION),
                required(instructions, "Instruções", MAX_CONTENT), enabled));
    }

    @Transactional
    public AiSkill updateSkill(long id, String name, String description,
                               String instructions, boolean enabled) {
        AiSkill skill = skills.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Skill não encontrada."));
        skill.setName(required(name, "Nome", MAX_NAME));
        skill.setDescription(optional(description, MAX_DESCRIPTION));
        skill.setInstructions(required(instructions, "Instruções", MAX_CONTENT));
        skill.setEnabled(enabled);
        return skills.save(skill);
    }

    @Transactional
    public void deleteSkill(long id) {
        if (!skills.existsById(id)) throw new IllegalArgumentException("Skill não encontrada.");
        skills.deleteById(id);
    }

    /** Trecho seguro e limitado que será acrescentado à mensagem de sistema. */
    @Transactional(readOnly = true)
    public String promptContext() {
        StringBuilder out = new StringBuilder();
        List<AiMemory> enabledMemories = memories.findByEnabledTrueOrderByUpdatedAtDesc();
        if (!enabledMemories.isEmpty()) {
            out.append("\n\nMEMÓRIAS ADMINISTRATIVAS:\n");
            for (AiMemory memory : enabledMemories) {
                appendLimited(out, "- " + memory.getTitle() + ": " + memory.getContent() + "\n");
            }
        }
        List<AiSkill> enabledSkills = skills.findByEnabledTrueOrderByUpdatedAtDesc();
        if (!enabledSkills.isEmpty()) {
            out.append("\nSKILLS/INSTRUÇÕES ATIVAS:\n");
            for (AiSkill skill : enabledSkills) {
                appendLimited(out, "- " + skill.getName() + ": " + skill.getInstructions() + "\n");
            }
        }
        return out.toString();
    }

    private static void appendLimited(StringBuilder out, String value) {
        if (out.length() >= MAX_PROMPT_CONTEXT) return;
        int remaining = MAX_PROMPT_CONTEXT - out.length();
        out.append(value, 0, Math.min(value.length(), remaining));
    }

    private static String required(String value, String label, int max) {
        String clean = value == null ? "" : value.strip();
        if (clean.isBlank()) throw new IllegalArgumentException(label + " é obrigatório.");
        if (clean.length() > max) throw new IllegalArgumentException(label + " excede " + max + " caracteres.");
        return clean;
    }

    private static String optional(String value, int max) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > max) throw new IllegalArgumentException("Descrição excede " + max + " caracteres.");
        return clean;
    }
}

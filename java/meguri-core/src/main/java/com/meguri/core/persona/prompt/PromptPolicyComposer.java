package com.meguri.core.persona.prompt;

import com.meguri.core.capability.PromptSkillContextContract;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Produces typed blocks; retrieved content is always data, never executable policy. */
public final class PromptPolicyComposer {
    public List<PromptBlock> compose(EffectivePersonaState state, ContextBundle context) {
        return compose(state, context, List.of());
    }

    public List<PromptBlock> compose(
            EffectivePersonaState state,
            ContextBundle context,
            List<PromptSkillContextContract.ContextInput> promptSkills) {
        Objects.requireNonNull(context, "context");
        List<PromptBlock> blocks = authorityBlocks(state);
        for (ContextBundle.Block block : context.blocks()) {
            blocks.add(contextBlock(block, context.buildRevision()));
        }
        if (promptSkills != null) {
            for (PromptSkillContextContract.ContextInput skill : promptSkills) {
                blocks.add(promptSkillBlock(Objects.requireNonNull(skill, "prompt skill")));
            }
        }
        return List.copyOf(blocks);
    }

    public List<PromptBlock> compose(EffectivePersonaState state, List<ExternalData> external) {
        List<PromptBlock> blocks = authorityBlocks(state);
        if (external != null) for (ExternalData data : external) {
            if (data == null || data.source() == null) throw new IllegalArgumentException("external data is required");
            if (data.source() != Source.RAG && data.source() != Source.WEB && data.source() != Source.TOOL && data.source() != Source.MEMORY)
                throw new IllegalArgumentException("external data source is not allowed");
            blocks.add(new PromptBlock(Role.USER_DATA, data.source(), Trust.UNTRUSTED, data.provenance(), data.revision(),
                    wrap("untrusted-data", data.content())));
        }
        return List.copyOf(blocks);
    }

    private static List<PromptBlock> authorityBlocks(EffectivePersonaState state) {
        Objects.requireNonNull(state, "state");
        List<PromptBlock> blocks = new ArrayList<>();
        blocks.add(new PromptBlock(Role.SYSTEM, Source.PERSONA, Trust.TRUSTED,
                "persona:" + state.persona().personaId(), state.persona().revision(),
                "Core traits: " + state.persona().coreTraits() + "\nHard boundaries: " + state.persona().hardBoundaries()));
        blocks.add(new PromptBlock(Role.DEVELOPER, Source.RUNTIME, Trust.TRUSTED,
                "persona-runtime", state.policyRevision(),
                "Effective mode=" + state.mode() + "; relationship=" + state.relationship().stage()
                        + "; allowed=" + state.envelope().allowedBehaviors() + "; blocked=" + state.envelope().blockedBehaviors()));
        return blocks;
    }

    private static PromptBlock contextBlock(ContextBundle.Block block, String revision) {
        Source source = switch (block.blockType()) {
            case MEMORY -> Source.MEMORY;
            case RETRIEVAL -> block.sourceIds().stream().anyMatch("WEB"::equalsIgnoreCase)
                    ? Source.WEB : Source.RAG;
            case TOOL_RESULT -> Source.TOOL;
            default -> Source.CONTEXT;
        };
        Trust trust = switch (block.trust()) {
            case SYSTEM -> Trust.REVIEWED;
            case USER -> Trust.USER;
            case APPROVED_MEMORY -> Trust.REVIEWED;
            case UNTRUSTED_EXTERNAL -> Trust.UNTRUSTED;
        };
        String provenance = block.sourceIds().isEmpty()
                ? "context:" + block.blockType().name().toLowerCase(java.util.Locale.ROOT)
                : String.join(",", block.sourceIds());
        String tag = trust == Trust.UNTRUSTED ? "untrusted-data"
                : trust == Trust.USER ? "user-context" : "reviewed-context";
        return new PromptBlock(Role.USER_DATA, source, trust, provenance, revision,
                wrap(tag, block.content()));
    }

    private static PromptBlock promptSkillBlock(PromptSkillContextContract.ContextInput skill) {
        boolean trusted = skill.trust()
                == PromptSkillContextContract.Trust.TRUSTED_PROMPT_SKILL;
        var provenance = skill.provenance();
        return new PromptBlock(
                trusted ? Role.DEVELOPER : Role.USER_DATA,
                Source.PROMPT_SKILL,
                trusted ? Trust.TRUSTED : Trust.UNTRUSTED,
                provenance.capabilityId() + ":" + provenance.sourceId(),
                provenance.capabilityVersion(),
                trusted ? skill.content() : wrap("untrusted-data", skill.content()));
    }

    private static String wrap(String tag, String value) {
        return "<" + tag + ">\n" + escape(value) + "\n</" + tag + ">";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    public enum Role { SYSTEM, DEVELOPER, USER_DATA }
    public enum Source { PERSONA, RUNTIME, CONTEXT, MEMORY, RAG, WEB, TOOL, PROMPT_SKILL }
    public enum Trust { TRUSTED, REVIEWED, USER, UNTRUSTED }
    public record PromptBlock(Role role, Source source, Trust trust, String provenance, String revision, String content) { }
    public record ExternalData(Source source, String provenance, String revision, String content) { }
}

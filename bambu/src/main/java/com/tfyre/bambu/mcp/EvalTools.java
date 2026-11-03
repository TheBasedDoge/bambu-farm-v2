package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.AiPromptService;
import com.tfyre.bambu.printer.BedDiffService;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 3 AI evaluation tools (docs/MCP-PLAN.md): {@code label_frame} records ground truth for saved check frames,
 * {@code eval_prompt} re-runs a prompt (the live one or a draft) over saved frames through the real
 * {@link OllamaService} parsing and scores it against the labels. Nothing here changes the live prompts.
 */
@Singleton
public class EvalTools {

    /** Each frame is a full model call (~3-10 s on the farm's GPU) and shares Ollama with the live checks. */
    static final int MAX_FRAMES = 12;

    @Inject
    PrintAiService ai;
    @Inject
    AiTools aiTools;
    @Inject
    AiLabels labels;
    @Inject
    OllamaService ollama;
    @Inject
    AiPromptService prompts;
    @Inject
    BedDiffService bedDiff;
    @Inject
    McpAudit audit;

    // ---------------------------------------------------------------------------------------------------------
    // label_frame
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "label_frame", description = """
            Records what was really in a saved AI check frame, for scoring prompts with eval_prompt: label \
            problem (aliases: failure, missed, not_clear) or ok (aliases: false_alarm, clear). Use the user's \
            judgement, or your own only after looking at the frame with ai_frame and saying so in the note. A \
            blank label removes it. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Label AI frame", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse labelFrame(
            @ToolArg(description = "Frame id from ai_history, e.g. P1S-3@1793555994513") final String frameId,
            @ToolArg(description = "problem or ok (or an alias); blank to remove the label", required = false) final String label,
            @ToolArg(description = "Why - what is in the frame, who judged it", required = false) final String note) {
        return audit.run("label_frame", McpAudit.args("frameId", frameId, "label", label, "note", note), () -> {
            if (audit.caller().orElse(null) != McpAccessFilter.Access.CONTROL) {
                return ToolResponse.error("this tool needs the MCP control token; this connection uses the read token");
            }
            final Optional<PrintAiService.CheckRecord> rec = aiTools.find(frameId);
            if (rec.isEmpty()) {
                return ToolResponse.error("no AI check with frame id " + frameId + " (see ai_history)");
            }
            final String id = AiTools.frameId(rec.get());
            if (HistoryTools.blank(label)) {
                labels.set(id, null, null, null);
                return ToolResponse.success("removed the label on " + id);
            }
            final Optional<String> truth = AiLabels.truthOf(label);
            if (truth.isEmpty()) {
                return ToolResponse.error("label must be problem or ok (or failure / missed / not_clear / false_alarm / clear)");
            }
            labels.set(id, truth.get(), label.trim(), note);
            final String recorded = AiTools.verdictOf(rec.get());
            final boolean modelRight = "good".equals(recorded) ? "ok".equals(truth.get())
                    : "problem".equals(recorded) && "problem".equals(truth.get());
            return ToolResponse.success("labelled %s as %s; the live check said %s (%s)".formatted(id, truth.get(), recorded,
                    "incomplete".equals(recorded) ? "no verdict" : modelRight ? "correct" : "WRONG"));
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // eval_prompt
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "eval_prompt", description = """
            Re-runs an AI check prompt over saved frames and scores it. Uses the live prompt for each frame's \
            check type unless a draft prompt is given (the draft must end its answer format the same way, e.g. \
            Problems/Confidence/Reason for failure checks). Pick frames by id, or let it choose the newest \
            labelled frames of a check type. Returns per frame the recorded verdict, the label, the new verdict \
            and reason, and totals: agreement with labels and how many verdicts changed. Bed-clear frames are \
            cropped exactly as the live check crops them. Extrusion checks (two frames) are not supported. Each \
            frame is a full model call (max 12 per call) and shares Ollama with the live checks. Scores the model's own \
            verdict - the live check's pixel backstop and second-opinion pass are not applied. Changes nothing.""",
            annotations = @Tool.Annotations(title = "Evaluate AI prompt", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse evalPrompt(
            @ToolArg(description = "Frame ids from ai_history; omit to use the newest labelled frames of checkType", required = false) final List<String> frameIds,
            @ToolArg(description = "bed-clear, failure or first-layer - needed when frameIds is omitted, and to pick the live prompt for a draft", required = false) final String checkType,
            @ToolArg(description = "Draft prompt text to try instead of the live one", required = false) final String prompt,
            @ToolArg(description = "Pass each frame's original printer context (HMS hints) like the live check did", required = false, defaultValue = "true") final boolean useContext,
            @ToolArg(description = "Frames to run when picking automatically (1-12)", required = false, defaultValue = "6") final int limit) {
        return audit.run("eval_prompt", McpAudit.args("frameIds", frameIds, "checkType", checkType,
                "prompt", prompt == null ? null : prompt.length() + " chars", "useContext", useContext, "limit", limit),
                () -> evalBody(frameIds, checkType, prompt, useContext, limit));
    }

    private ToolResponse evalBody(final List<String> frameIds, final String checkType, final String prompt,
            final boolean useContext, final int limit) {
        if (!ollama.isEnabled()) {
            return ToolResponse.error("Ollama is not configured");
        }
        final List<PrintAiService.CheckRecord> picked = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        if (frameIds != null && !frameIds.isEmpty()) {
            for (final String id : frameIds) {
                aiTools.find(id).ifPresentOrElse(picked::add, () -> missing.add(id));
            }
        } else {
            if (HistoryTools.blank(checkType)) {
                return ToolResponse.error("give frameIds, or a checkType to pick labelled frames of");
            }
            final int n = Math.max(1, Math.min(MAX_FRAMES, limit));
            for (final PrintAiService.CheckRecord r : ai.getHistory()) {
                if (checkType.equalsIgnoreCase(r.checkType()) && labels.get(AiTools.frameId(r)).isPresent()) {
                    picked.add(r);
                    if (picked.size() >= n) {
                        break;
                    }
                }
            }
            if (picked.isEmpty()) {
                return ToolResponse.error("no labelled %s frames yet - label some with label_frame, or pass frameIds".formatted(checkType));
            }
        }
        if (picked.size() > MAX_FRAMES) {
            return ToolResponse.error("at most %d frames per call (got %d)".formatted(MAX_FRAMES, picked.size()));
        }

        final List<Map<String, Object>> rows = new ArrayList<>();
        int scored = 0;
        int right = 0;
        int changed = 0;
        int noAnswer = 0;
        for (final PrintAiService.CheckRecord r : picked) {
            final String id = AiTools.frameId(r);
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("frame", id);
            m.put("type", r.checkType());
            final Optional<AiPromptService.PromptType> type = typeOf(r.checkType());
            final String recorded = AiTools.verdictOf(r);
            m.put("recorded", recorded);
            final Optional<AiLabels.Label> label = labels.get(id);
            label.ifPresent(l -> m.put("label", l.truth()));
            if (type.isEmpty()) {
                m.put("skipped", "check type %s is not supported".formatted(r.checkType()));
                rows.add(m);
                continue;
            }
            if (!HistoryTools.blank(checkType) && !HistoryTools.blank(prompt) && !checkType.equalsIgnoreCase(r.checkType())) {
                m.put("skipped", "the draft prompt is for %s".formatted(checkType));
                rows.add(m);
                continue;
            }
            final byte[] frame = ai.frameOf(r);
            if (frame == null || frame.length == 0) {
                m.put("skipped", "frame no longer stored");
                rows.add(m);
                continue;
            }
            final byte[] framed = type.get() == AiPromptService.PromptType.BED_CLEAR ? bedDiff.cropForAi(r.printer(), frame, 0) : frame;
            final String text = HistoryTools.blank(prompt) ? prompts.getPrompt(type.get()) : prompt;
            final Optional<String> context = useContext ? Optional.ofNullable(r.context()) : Optional.empty();
            final Optional<OllamaService.AiResult> result = ollama.analyzePrompt(framed, text, type.get().positiveKeyword(), context);
            if (result.isEmpty()) {
                noAnswer++;
                m.put("now", "no answer (Ollama error or timeout)");
                rows.add(m);
                continue;
            }
            final boolean good = positiveMeansGood(type.get()) == result.get().positive();
            final String now = good ? "good" : "problem";
            m.put("now", now);
            // AiResult's severity is from the model's side of the question (a "positive" failure answer reads as
            // OK); the live check re-derives it from the outcome, so do the same here.
            m.put("severity", OllamaService.severityFor(good, result.get().description()).name());
            m.put("reason", FarmTools.truncate(result.get().description(), 300));
            if (!recorded.equals(now)) {
                changed++;
                m.put("changed", true);
            }
            if (label.isPresent()) {
                scored++;
                final boolean ok = "ok".equals(label.get().truth()) == good;
                if (ok) {
                    right++;
                }
                m.put("matchesLabel", ok);
            }
            rows.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("prompt", HistoryTools.blank(prompt) ? "live" : "draft (%d chars)".formatted(prompt.length()));
        out.put("frames", rows.size());
        out.put("labelled", scored);
        if (scored > 0) {
            out.put("matchesLabels", "%d/%d".formatted(right, scored));
        }
        out.put("changedFromRecorded", changed);
        if (noAnswer > 0) {
            out.put("noAnswer", noAnswer);
        }
        if (!missing.isEmpty()) {
            out.put("notFound", missing);
        }
        out.put("results", rows);
        return audit.json(out);
    }

    static Optional<AiPromptService.PromptType> typeOf(final String checkType) {
        for (final AiPromptService.PromptType t : AiPromptService.PromptType.values()) {
            if (t.key().equalsIgnoreCase(checkType)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** A positive answer is good news for bed-clear (YES = clear) and first-layer (GOOD), bad news for failure. */
    static boolean positiveMeansGood(final AiPromptService.PromptType t) {
        return t != AiPromptService.PromptType.FAILURE;
    }
}

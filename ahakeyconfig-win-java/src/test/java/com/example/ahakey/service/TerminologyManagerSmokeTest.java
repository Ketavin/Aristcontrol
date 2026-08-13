package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Standalone, no-network smoke test for terminology merging and safe correction boundaries. */
public final class TerminologyManagerSmokeTest {

    private TerminologyManagerSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("ahakey-terminology-test-");
        try {
            Path terminology = temp.resolve("terminology.txt");
            Path corrections = temp.resolve("corrections.tsv");
            System.setProperty("qwen.terminology.path", terminology.toString());
            System.setProperty("qwen.corrections.path", corrections.toString());

            // Simulate an upgrade from an older installation with user entries.
            StringBuilder userTerminology = new StringBuilder(
                "# user terminology\nExistingCustomTerm\n林知夏\n周明远\n小星\n"
            );
            for (int index = 1; index <= 30; index++) {
                userTerminology.append(String.format("自定义姓名%02d%n", index));
            }
            Files.writeString(terminology, userTerminology, StandardCharsets.UTF_8);
            Files.writeString(
                corrections,
                "# user corrections\nexisting phrase\tExisting Phrase\t1.00\n",
                StandardCharsets.UTF_8
            );

            TerminologyManager manager = new TerminologyManager(ModelConfig.getInstance());
            manager.initialize();

            String glossary = manager.buildPromptGlossary();
            require(glossary.contains("vibe coding"), "missing user-provided coding terminology");
            require(glossary.contains("Qwen3-ASR-Flash"), "missing model terminology");
            require(glossary.contains("BloombergNEF"), "missing research terminology");
            require(glossary.contains("Ctrl+C"), "missing copy shortcut terminology");
            require(glossary.contains("Ctrl+V"), "missing paste shortcut terminology");
            require(glossary.contains("OpenClaw"), "missing agent platform terminology");
            require(glossary.contains("Cloudflare Workers"), "missing infrastructure terminology");
            require(glossary.contains("Tailscale Peer Relay"), "missing remote-network terminology");
            require(glossary.contains("Sharpe ratio"), "missing finance terminology");
            require(glossary.contains("WACC"), "missing valuation terminology");
            require(glossary.contains("ROIC"), "missing investment terminology");
            require(glossary.contains("林知夏"), "missing user-provided personal name 林知夏");
            require(glossary.contains("周明远"), "missing user-provided personal name 周明远");
            require(glossary.contains("小星"), "missing user-provided personal name 小星");
            require(
                manager.findTermsInText("请通知林知夏和周明远").containsAll(List.of("林知夏", "周明远")),
                "user-provided personal names were not exposed to the rewrite guard"
            );
            require(glossary.contains("ExistingCustomTerm"), "existing user terminology was not preserved");
            require(glossary.contains("自定义姓名30"), "later user-provided names were truncated from the prompt");

            String input = "Use control c and control v for web coding in open ai with codex cli, qwen 3 asr flash, api key and speech to text.";
            String expected = "Use Ctrl+C and Ctrl+V for vibe coding in OpenAI with Codex CLI, Qwen3-ASR-Flash, API key and speech-to-text.";
            require(expected.equals(manager.applyHighConfidenceCorrections(input)), "canonical correction failed");
            require(
                "Existing Phrase".equals(manager.applyHighConfidenceCorrections("existing phrase")),
                "existing user correction was not preserved"
            );

            String boundaryInput = "The open aircraft repo is called githubber.";
            require(boundaryInput.equals(manager.applyHighConfidenceCorrections(boundaryInput)), "word boundary was not preserved");

            Files.writeString(
                corrections,
                System.lineSeparator() + "cloud code\tClaude Code\t0.70" + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND
            );
            require(
                "cloud code".equals(manager.applyHighConfidenceCorrections("cloud code")),
                "low-confidence rule should not auto-apply"
            );

            System.out.println("TerminologyManager smoke test passed");
        } finally {
            try (var paths = Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort cleanup for a disposable test directory.
                    }
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

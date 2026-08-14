package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maintains a user-editable terminology library and a deliberately conservative
 * literal correction layer. Fuzzy replacements are intentionally excluded:
 * only explicitly listed rules at or above the configured confidence threshold
 * are applied to recognized text.
 */
public final class TerminologyManager {

    private static final Logger logger = LoggerFactory.getLogger(TerminologyManager.class);
    private static final String DEFAULT_TERMINOLOGY_RESOURCE = "/default-terminology.txt";
    private static final String DEFAULT_CORRECTIONS_RESOURCE = "/default-corrections.tsv";
    private static final int MAX_PROMPT_TERMS = 500;
    private static final int MAX_PROMPT_CHARACTERS = 6_000;
    private static final int CASE_INSENSITIVE_UNICODE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern CLOUD_TOKEN_PATTERN = Pattern.compile(
        "(?<![\\p{L}\\p{N}])cloud(?![\\p{L}\\p{N}])",
        CASE_INSENSITIVE_UNICODE
    );
    private static final Pattern CLOUD_ONLY_UTTERANCE_PATTERN = Pattern.compile(
        "^\\s*cloud\\s*[。！？!?….]*\\s*$",
        CASE_INSENSITIVE_UNICODE
    );
    private static final Pattern CLAUDE_MODEL_CONTEXT_PATTERN = Pattern.compile(
        "(?:\\b(?:fable|opus|sonnet|haiku|model|gpt|gemini|deepseek|qwen|glm|kimi|minimax)\\b"
            + "|模型|大模型|写代码|改代码|编程)",
        CASE_INSENSITIVE_UNICODE
    );

    private final ModelConfig config;
    private final Path terminologyPath;
    private final Path correctionsPath;

    public TerminologyManager(ModelConfig config) {
        this.config = config;
        this.terminologyPath = resolveUserPath(config.getQwenTerminologyPath());
        this.correctionsPath = resolveUserPath(config.getQwenCorrectionsPath());
    }

    public void initialize() {
        try {
            mergeTerminologyDefaults();
            mergeCorrectionDefaults();
            logger.info(
                "术语库已就绪: {} 个术语，{} 条高置信度纠错规则",
                loadTerms().size(),
                loadCorrectionRules().size()
            );
        } catch (IOException e) {
            logger.warn("外部术语库初始化失败，继续使用内置词库: {}", e.getMessage());
        }
    }

    public String buildPromptGlossary() {
        List<String> terms = loadTermsSafely();
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String term : terms) {
            int addedLength = term.length() + (result.isEmpty() ? 0 : 1);
            if (count >= MAX_PROMPT_TERMS || result.length() + addedLength > MAX_PROMPT_CHARACTERS) {
                break;
            }
            if (!result.isEmpty()) {
                result.append('、');
            }
            result.append(term);
            count++;
        }
        return result.toString();
    }

    public String applyHighConfidenceCorrections(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String corrected = applyContextualClaudeCorrection(text);
        for (CorrectionRule rule : loadCorrectionRulesSafely()) {
            Matcher matcher = rule.pattern().matcher(corrected);
            if (matcher.find()) {
                corrected = matcher.replaceAll(Matcher.quoteReplacement(rule.replacement()));
                logger.debug("应用高置信度纠错: {} -> {}", rule.source(), rule.replacement());
            }
        }
        return corrected;
    }

    private String applyContextualClaudeCorrection(String text) {
        Matcher cloudMatcher = CLOUD_TOKEN_PATTERN.matcher(text);
        if (!cloudMatcher.find()) {
            return text;
        }
        boolean standaloneUtterance = CLOUD_ONLY_UTTERANCE_PATTERN.matcher(text).matches();
        boolean modelContext = CLAUDE_MODEL_CONTEXT_PATTERN.matcher(text).find();
        if (!standaloneUtterance && !modelContext) {
            return text;
        }
        String corrected = cloudMatcher.replaceAll("Claude");
        logger.debug("应用 Claude 上下文纠错: cloud -> Claude");
        return corrected;
    }

    public Path getTerminologyPath() {
        return terminologyPath;
    }

    public Path getCorrectionsPath() {
        return correctionsPath;
    }

    List<String> findTermsInText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String term : loadTermsSafely()) {
            if (!term.isEmpty() && text.contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    private List<String> loadTermsSafely() {
        try {
            return loadTerms();
        } catch (IOException e) {
            logger.warn("读取用户术语库失败，改用内置词库: {}", e.getMessage());
            return parseTerminology(readResourceLinesSafely(DEFAULT_TERMINOLOGY_RESOURCE));
        }
    }

    private List<String> loadTerms() throws IOException {
        List<String> defaults = parseTerminology(readResourceLines(DEFAULT_TERMINOLOGY_RESOURCE));
        List<String> legacy = parseLegacyGlossary(config.getQwenGlossary());
        List<String> userTerms = Files.isRegularFile(terminologyPath)
            ? parseTerminology(Files.readAllLines(terminologyPath, StandardCharsets.UTF_8))
            : List.of();

        Set<String> bundledKeys = new LinkedHashSet<>();
        for (String term : defaults) {
            bundledKeys.add(term.toLowerCase(Locale.ROOT));
        }
        LinkedHashMap<String, String> terms = new LinkedHashMap<>();
        // User-added terms must reach the prompt even after bundled defaults grow.
        addTermsExcluding(terms, userTerms, bundledKeys);
        addTermsExcluding(terms, legacy, bundledKeys);
        addTerms(terms, defaults);
        addTerms(terms, legacy);
        addTerms(terms, userTerms);
        return new ArrayList<>(terms.values());
    }

    private void addTermsExcluding(
        Map<String, String> destination,
        List<String> source,
        Set<String> excludedKeys
    ) {
        for (String term : source) {
            String key = term.toLowerCase(Locale.ROOT);
            if (!excludedKeys.contains(key)) {
                destination.putIfAbsent(key, term);
            }
        }
    }

    private void addTerms(Map<String, String> destination, List<String> source) {
        for (String term : source) {
            destination.putIfAbsent(term.toLowerCase(Locale.ROOT), term);
        }
    }

    private List<CorrectionRule> loadCorrectionRulesSafely() {
        try {
            return loadCorrectionRules();
        } catch (IOException e) {
            logger.warn("读取用户纠错规则失败，改用内置规则: {}", e.getMessage());
            return parseCorrectionRules(readResourceLinesSafely(DEFAULT_CORRECTIONS_RESOURCE));
        }
    }

    private List<CorrectionRule> loadCorrectionRules() throws IOException {
        LinkedHashMap<String, CorrectionRule> rules = new LinkedHashMap<>();
        for (CorrectionRule rule : parseCorrectionRules(readResourceLines(DEFAULT_CORRECTIONS_RESOURCE))) {
            rules.put(rule.source().toLowerCase(Locale.ROOT), rule);
        }
        if (Files.isRegularFile(correctionsPath)) {
            for (CorrectionRule rule : parseCorrectionRules(Files.readAllLines(correctionsPath, StandardCharsets.UTF_8))) {
                // User rules override bundled rules with the same source phrase.
                rules.put(rule.source().toLowerCase(Locale.ROOT), rule);
            }
        }
        List<CorrectionRule> result = new ArrayList<>(rules.values());
        result.sort(Comparator.comparingInt((CorrectionRule rule) -> rule.source().length()).reversed());
        return result;
    }

    private List<String> parseTerminology(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String value = stripBom(line).trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> parseLegacyGlossary(String glossary) {
        List<String> result = new ArrayList<>();
        if (glossary == null || glossary.isBlank()) {
            return result;
        }
        for (String value : glossary.split(",")) {
            if (!value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private List<CorrectionRule> parseCorrectionRules(List<String> lines) {
        List<CorrectionRule> result = new ArrayList<>();
        double threshold = config.getQwenCorrectionMinConfidence();
        for (String line : lines) {
            String value = stripBom(line).trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String[] parts = value.split("\\t", -1);
            if (parts.length < 2) {
                logger.warn("忽略格式无效的纠错规则: {}", value);
                continue;
            }
            String source = parts[0].trim();
            String replacement = parts[1].trim();
            double confidence = 1.0;
            if (parts.length >= 3 && !parts[2].isBlank()) {
                try {
                    confidence = Double.parseDouble(parts[2].trim());
                } catch (NumberFormatException e) {
                    logger.warn("忽略置信度无效的纠错规则: {}", value);
                    continue;
                }
            }
            if (source.isEmpty() || replacement.isEmpty() || source.equals(replacement) || confidence < threshold) {
                continue;
            }
            result.add(new CorrectionRule(source, replacement, confidence, compileLiteralPattern(source)));
        }
        return result;
    }

    private void mergeTerminologyDefaults() throws IOException {
        List<String> defaults = readResourceLines(DEFAULT_TERMINOLOGY_RESOURCE);
        ensureParentDirectory(terminologyPath);
        if (!Files.exists(terminologyPath)) {
            Files.write(terminologyPath, defaults, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return;
        }

        Set<String> existing = new LinkedHashSet<>();
        for (String term : parseTerminology(Files.readAllLines(terminologyPath, StandardCharsets.UTF_8))) {
            existing.add(term.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String term : parseTerminology(defaults)) {
            if (existing.add(term.toLowerCase(Locale.ROOT))) {
                missing.add(term);
            }
        }
        appendMissingEntries(terminologyPath, "# --- 新版内置术语合并 ---", missing);
    }

    private void mergeCorrectionDefaults() throws IOException {
        List<String> defaults = readResourceLines(DEFAULT_CORRECTIONS_RESOURCE);
        ensureParentDirectory(correctionsPath);
        if (!Files.exists(correctionsPath)) {
            Files.write(correctionsPath, defaults, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return;
        }

        Set<String> existingSources = new LinkedHashSet<>();
        for (String line : Files.readAllLines(correctionsPath, StandardCharsets.UTF_8)) {
            String value = stripBom(line).trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                String[] parts = value.split("\\t", -1);
                if (parts.length >= 2 && !parts[0].isBlank()) {
                    existingSources.add(parts[0].trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String line : defaults) {
            String value = stripBom(line).trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String[] parts = value.split("\\t", -1);
            if (parts.length >= 2 && existingSources.add(parts[0].trim().toLowerCase(Locale.ROOT))) {
                missing.add(value);
            }
        }
        appendMissingEntries(correctionsPath, "# --- 新版内置规则合并 ---", missing);
    }

    private void appendMissingEntries(Path path, String heading, List<String> missing) throws IOException {
        if (missing.isEmpty()) {
            return;
        }
        List<String> addition = new ArrayList<>();
        addition.add("");
        addition.add(heading);
        addition.addAll(missing);
        Files.write(path, addition, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private List<String> readResourceLines(String resource) throws IOException {
        try (InputStream input = TerminologyManager.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("缺少内置资源: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private List<String> readResourceLinesSafely(String resource) {
        try {
            return readResourceLines(resource);
        } catch (IOException e) {
            logger.error("读取内置术语资源失败: {}", resource);
            return List.of();
        }
    }

    private void ensureParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Path resolveUserPath(String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("Windows LOCALAPPDATA 不可用");
        }
        return Path.of(localAppData).resolve(configured).normalize();
    }

    private Pattern compileLiteralPattern(String source) {
        String pattern = Pattern.quote(source);
        if (Character.isLetterOrDigit(source.codePointAt(0))) {
            pattern = "(?<![\\p{L}\\p{N}])" + pattern;
        }
        int lastCodePoint = source.codePointBefore(source.length());
        if (Character.isLetterOrDigit(lastCodePoint)) {
            pattern = pattern + "(?![\\p{L}\\p{N}])";
        }
        return Pattern.compile(pattern, CASE_INSENSITIVE_UNICODE);
    }

    private String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record CorrectionRule(String source, String replacement, double confidence, Pattern pattern) {
    }
}

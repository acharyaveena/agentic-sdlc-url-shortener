package com.veenaacharya.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Flags credential-like content. Findings carry the line number and pattern name, never the value. */
public class SecretScanRule implements PolicyRule {

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("AWS access key id", Pattern.compile("AKIA[0-9A-Z]{16}"));
        PATTERNS.put("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        PATTERNS.put("GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"));
        PATTERNS.put("Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"));
        PATTERNS.put("hard-coded credential", Pattern.compile(
                "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\b\\s*[:=]\\s*[\"'][^\"'\\s$]{8,}[\"']"));
        PATTERNS.put("credential in config", Pattern.compile(
                "(?i)^\\s*[\\w.\\-]*(password|secret|token|api[_-]?key)[\\w.\\-]*\\s*[:=]\\s*(?!\\$\\{)[^\\s#\"'$]{8,}\\s*$"));
    }

    @Override
    public String name() {
        return "secret-scan";
    }

    @Override
    public List<Violation> check(List<FileChange> changes, PolicyContext context) {
        List<Violation> violations = new ArrayList<>();
        for (FileChange change : changes) {
            String[] lines = change.content().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                for (Map.Entry<String, Pattern> pattern : PATTERNS.entrySet()) {
                    Matcher matcher = pattern.getValue().matcher(lines[i]);
                    if (matcher.find()) {
                        violations.add(new Violation(name(), change.path(),
                                "possible " + pattern.getKey() + " on line " + (i + 1)));
                        break;
                    }
                }
            }
        }
        return violations;
    }
}

package com.veenaacharya.agentic.orchestrator.evidence;

import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import com.veenaacharya.agentic.orchestrator.policy.PolicyContext;
import com.veenaacharya.agentic.orchestrator.policy.SecretScanRule;
import com.veenaacharya.agentic.orchestrator.policy.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks evidence files before they are written: no credentials, no local filesystem paths, no personal
 * email addresses. Findings give the file and line, never the offending text.
 */
public class EvidenceScanner {

    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[a-z]:\\\\{1,2}[a-z0-9_.$ -]");
    private static final Pattern UNIX_HOME_PATH = Pattern.compile("(?:/Users|/home)/[A-Za-z0-9_.-]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");
    private static final Set<String> HARMLESS_EMAIL_DOMAINS =
            Set.of("example.com", "example.org", "users.noreply.github.com");

    private final SecretScanRule secrets = new SecretScanRule();
    private final List<String> literalPaths;

    /** @param machineSpecificPaths exact paths of this machine (user home, repository root) to look for verbatim */
    public EvidenceScanner(List<String> machineSpecificPaths) {
        this.literalPaths = machineSpecificPaths.stream()
                .filter(p -> p != null && p.length() > 3)
                .flatMap(p -> Stream.of(p, p.replace('\\', '/'), p.replace("\\", "\\\\")))
                .distinct()
                .toList();
    }

    public List<String> scan(Map<String, String> files) {
        List<String> problems = new ArrayList<>();
        files.forEach((name, content) -> {
            for (Violation v : secrets.check(List.of(new FileChange(name, content)),
                    new PolicyContext("evidence", Map.of()))) {
                problems.add(v.path() + ": " + v.message());
            }
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (WINDOWS_PATH.matcher(line).find() || UNIX_HOME_PATH.matcher(line).find()
                        || literalPaths.stream().anyMatch(line::contains)) {
                    problems.add(name + ": local filesystem path on line " + (i + 1));
                }
                Matcher email = EMAIL.matcher(line);
                while (email.find()) {
                    if (!HARMLESS_EMAIL_DOMAINS.contains(email.group(1).toLowerCase())) {
                        problems.add(name + ": email address on line " + (i + 1));
                    }
                }
            }
        });
        return problems;
    }
}

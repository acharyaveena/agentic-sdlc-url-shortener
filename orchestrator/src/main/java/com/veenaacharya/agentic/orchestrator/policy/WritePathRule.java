package com.veenaacharya.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agents may write only inside {@code url-shortener/}, and each stage only to its own area:
 * code to main sources and the pom, tests to test sources, docs to the README and docs folder.
 */
public class WritePathRule implements PolicyRule {

    public static final String PRODUCT_DIR = "url-shortener";

    private static final Map<String, List<String>> STAGE_AREAS = Map.of(
            "code", List.of(PRODUCT_DIR + "/src/main/", PRODUCT_DIR + "/pom.xml"),
            "tests", List.of(PRODUCT_DIR + "/src/test/"),
            "docs", List.of(PRODUCT_DIR + "/README.md", PRODUCT_DIR + "/docs/"));

    @Override
    public String name() {
        return "write-path";
    }

    @Override
    public List<Violation> check(List<FileChange> changes, PolicyContext context) {
        List<Violation> violations = new ArrayList<>();
        for (FileChange change : changes) {
            String path = change.path();
            String problem = pathProblem(path);
            if (problem == null && !inside(path, PRODUCT_DIR + "/")) {
                problem = "writes are limited to " + PRODUCT_DIR + "/";
            }
            List<String> areas = STAGE_AREAS.get(context.stageId());
            if (problem == null && areas != null && areas.stream().noneMatch(a -> inside(path, a))) {
                problem = "stage " + context.stageId() + " may only write to " + areas;
            }
            if (problem != null) {
                violations.add(new Violation(name(), String.valueOf(path), problem));
            }
        }
        return violations;
    }

    private static String pathProblem(String path) {
        if (path == null || path.isBlank()) {
            return "path is empty";
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0) {
            return "path must be relative and use forward slashes";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "path must be normalised (no '.', '..' or empty segments)";
            }
        }
        return null;
    }

    /** A trailing-slash area is a folder prefix; any other area is one exact file. */
    private static boolean inside(String path, String area) {
        return area.endsWith("/") ? path.startsWith(area) : path.equals(area);
    }
}

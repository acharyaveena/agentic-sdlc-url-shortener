package com.veenaacharya.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A pom.xml may only use dependencies and plugins from allowed groupIds, and may not add remote repositories. */
public class DependencyAllowlistRule implements PolicyRule {

    private static final Pattern DEPENDENCY = Pattern.compile("(?s)<(dependency|plugin)>(.*?)</\\1>");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern REPOSITORY = Pattern.compile("<(repository|pluginRepository|repositories|pluginRepositories)>");

    private final Set<String> allowedGroupIds;

    public DependencyAllowlistRule(Set<String> allowedGroupIds) {
        this.allowedGroupIds = Set.copyOf(allowedGroupIds);
    }

    @Override
    public String name() {
        return "dependency-allowlist";
    }

    @Override
    public List<Violation> check(List<FileChange> changes, PolicyContext context) {
        List<Violation> violations = new ArrayList<>();
        for (FileChange change : changes) {
            if (!change.path().endsWith("pom.xml")) {
                continue;
            }
            Matcher block = DEPENDENCY.matcher(change.content());
            while (block.find()) {
                Matcher group = GROUP_ID.matcher(block.group(2));
                if (group.find() && !allowedGroupIds.contains(group.group(1))) {
                    violations.add(new Violation(name(), change.path(),
                            block.group(1) + " groupId '" + group.group(1) + "' is not on the allowlist"));
                }
            }
            if (REPOSITORY.matcher(change.content()).find()) {
                violations.add(new Violation(name(), change.path(), "adding remote repositories is not allowed"));
            }
        }
        return violations;
    }
}

package com.veenaacharya.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;

/** Runs every rule and collects all violations, so a reviewer sees the full picture at once. */
public class DefaultPolicyGuard implements PolicyGuard {

    private final List<PolicyRule> rules;

    public DefaultPolicyGuard(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public List<Violation> check(List<FileChange> changes, PolicyContext context) {
        List<Violation> violations = new ArrayList<>();
        for (PolicyRule rule : rules) {
            violations.addAll(rule.check(changes, context));
        }
        return violations;
    }

    @Override
    public List<String> ruleNames() {
        return rules.stream().map(PolicyRule::name).toList();
    }
}

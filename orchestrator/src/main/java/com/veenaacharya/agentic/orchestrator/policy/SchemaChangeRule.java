package com.veenaacharya.agentic.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Schema files may be written only if the approved design declares schema changes. */
public class SchemaChangeRule implements PolicyRule {

    private static final Pattern SCHEMA_FILE = Pattern.compile(
            "(?i).*(\\.sql|\\.ddl)$|.*/db/(migration|changelog)/.*");
    private static final Pattern SCHEMA_SECTION = Pattern.compile(
            "(?ims)^#{1,6}\\s*Schema changes\\s*:?\\s*$(.*?)(?=^#{1,6}\\s|\\z)");
    private static final Pattern SAYS_NONE = Pattern.compile("(?is)^\\W*none\\b.*");

    @Override
    public String name() {
        return "schema-change";
    }

    @Override
    public List<Violation> check(List<FileChange> changes, PolicyContext context) {
        List<Violation> violations = new ArrayList<>();
        boolean declared = designDeclaresSchemaChange(context.upstreamOutputs().get("design"));
        for (FileChange change : changes) {
            if (SCHEMA_FILE.matcher(change.path()).matches() && !declared) {
                violations.add(new Violation(name(), change.path(),
                        "schema change is not declared in the approved design"));
            }
        }
        return violations;
    }

    static boolean designDeclaresSchemaChange(String design) {
        if (design == null) {
            return false;
        }
        Matcher section = SCHEMA_SECTION.matcher(design);
        if (!section.find()) {
            return false;
        }
        String body = section.group(1).strip();
        return !body.isEmpty() && !SAYS_NONE.matcher(body).matches();
    }
}

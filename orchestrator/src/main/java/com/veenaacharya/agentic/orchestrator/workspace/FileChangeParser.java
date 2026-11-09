package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads agent output of the form {@code FILE: <path>} followed by a fenced block. A fence closes only on a
 * line with at least as many backticks as the opening one, so files that contain shorter fences survive.
 */
public class FileChangeParser {

    private static final Pattern FILE_LINE = Pattern.compile("^FILE:\\s*(.+?)\\s*$");
    private static final Pattern OPEN_FENCE = Pattern.compile("^(`{3,})[^`]*$");

    public List<FileChange> parse(String text) {
        List<FileChange> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\\R", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher file = FILE_LINE.matcher(lines[i]);
            if (!file.matches()) {
                i++;
                continue;
            }
            String path = cleanPath(file.group(1));
            int open = i + 1;
            while (open < lines.length && lines[open].isBlank()) {
                open++;
            }
            Matcher fence = open < lines.length ? OPEN_FENCE.matcher(lines[open]) : null;
            if (fence == null || !fence.matches()) {
                throw new FileChangeParseException("FILE: " + path + " is not followed by a code fence");
            }
            Pattern close = Pattern.compile("^`{" + fence.group(1).length() + ",}\\s*$");
            StringBuilder content = new StringBuilder();
            int line = open + 1;
            while (line < lines.length && !close.matcher(lines[line]).matches()) {
                content.append(lines[line]).append('\n');
                line++;
            }
            if (line >= lines.length) {
                throw new FileChangeParseException("code fence for " + path + " is never closed");
            }
            if (!seen.add(path)) {
                throw new FileChangeParseException("path appears twice: " + path);
            }
            changes.add(new FileChange(path, content.toString()));
            i = line + 1;
        }
        return changes;
    }

    private static String cleanPath(String raw) {
        return raw.replaceAll("^[`'\"]+|[`'\"]+$", "");
    }
}

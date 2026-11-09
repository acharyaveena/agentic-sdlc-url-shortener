package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import com.veenaacharya.agentic.orchestrator.policy.WritePathRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Writes already policy-approved files. It re-checks that every target stays inside the product
 * directory, including through symlinks, as a second line of defence behind the write-path rule.
 */
@Component
public class WorkspaceWriter {

    private final Path root;

    public WorkspaceWriter(WorkspaceRoot root) {
        this.root = root.path();
    }

    public List<String> write(List<FileChange> changes) {
        try {
            Path product = root.resolve(WritePathRule.PRODUCT_DIR);
            Files.createDirectories(product);
            Path productReal = product.toRealPath();
            List<String> written = new ArrayList<>();
            for (FileChange change : changes) {
                Path target = root.resolve(change.path()).normalize();
                if (!target.startsWith(product.normalize())) {
                    throw new SecurityException("refusing to write outside " + WritePathRule.PRODUCT_DIR + "/: " + change.path());
                }
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(target) || !target.getParent().toRealPath().startsWith(productReal)) {
                    throw new SecurityException("refusing to write through a symbolic link: " + change.path());
                }
                Files.writeString(target, change.content(), StandardCharsets.UTF_8);
                written.add(change.path());
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write files", e);
        }
    }
}

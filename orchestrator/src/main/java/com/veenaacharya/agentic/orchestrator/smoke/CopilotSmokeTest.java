package com.veenaacharya.agentic.orchestrator.smoke;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manual check that the Copilot SDK can authenticate, list models and answer a prompt.
 * Run: mvn -pl orchestrator exec:java -Dexec.mainClass=...CopilotSmokeTest [-Dexec.args="model-id"]
 */
public final class CopilotSmokeTest {

    private static final long TIMEOUT_SECONDS = 120;

    private CopilotSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        try (var client = new CopilotClient()) {
            client.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var auth = client.getAuthStatus().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("Authenticated: " + auth.isAuthenticated() + " as " + auth.getLogin()
                    + " (" + auth.getAuthType() + ")");
            if (!auth.isAuthenticated()) {
                System.err.println("Not signed in. Run `copilot` then `/login`, or set GH_TOKEN.");
                System.exit(2);
            }

            List<ModelInfo> models = client.listModels().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("Models available to this account:");
            models.forEach(m -> System.out.println("  " + m.getId() + "  (" + m.getName() + ")"));

            String model = args.length > 0 ? args[0] : pickDefault(models);
            System.out.println("Using model: " + model);

            // Text-only check: every tool/file permission request is refused.
            var config = new SessionConfig()
                    .setModel(model)
                    .setOnPermissionRequest((request, invocation) -> CompletableFuture.completedFuture(
                            PermissionRequestResult.reject("smoke test is text-only")));

            try (var session = client.createSession(config).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                var reply = session.sendAndWait("Reply with exactly the word: pong")
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String text = reply.getData().content();
                System.out.println("Reply: " + text);
                boolean ok = text != null && text.toLowerCase().contains("pong");
                System.out.println(ok ? "SMOKE TEST PASSED" : "SMOKE TEST FAILED: unexpected reply");
                System.exit(ok ? 0 : 1);
            }
        }
    }

    private static String pickDefault(List<ModelInfo> models) {
        return models.stream()
                .map(ModelInfo::getId)
                .filter(id -> id.toLowerCase().contains("sonnet"))
                .findFirst()
                .orElseGet(() -> models.get(0).getId());
    }
}

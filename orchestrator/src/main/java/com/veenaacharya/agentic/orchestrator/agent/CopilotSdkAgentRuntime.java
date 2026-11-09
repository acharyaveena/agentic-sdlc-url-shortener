package com.veenaacharya.agentic.orchestrator.agent;

import com.github.copilot.CopilotClient;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs each request in its own Copilot session. Sessions are text-only: every tool or file
 * permission request is refused, so agents can only return text and the orchestrator decides what to write.
 */
public class CopilotSdkAgentRuntime implements AgentRuntime, AutoCloseable {

    private final long timeoutSeconds;
    private CopilotClient client;

    public CopilotSdkAgentRuntime(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String complete(AgentRequest request) {
        try (var session = client().createSession(sessionConfig(request)).get(timeoutSeconds, TimeUnit.SECONDS)) {
            var reply = session.sendAndWait(request.userPrompt()).get(timeoutSeconds, TimeUnit.SECONDS);
            String text = reply == null ? null : reply.getData().content();
            if (text == null || text.isBlank()) {
                throw new AgentException("model " + request.model() + " returned an empty answer");
            }
            return text;
        } catch (TimeoutException e) {
            throw new AgentException("model " + request.model() + " timed out after " + timeoutSeconds + "s", e);
        } catch (ExecutionException e) {
            throw new AgentException("model call failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("interrupted while waiting for model", e);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("model call failed: " + e, e);
        }
    }

    static SessionConfig sessionConfig(AgentRequest request) {
        return new SessionConfig()
                .setModel(request.model())
                .setSystemMessage(new SystemMessageConfig()
                        .setMode(SystemMessageMode.APPEND)
                        .setContent(request.systemPrompt()))
                .setOnPermissionRequest((permission, invocation) -> CompletableFuture.completedFuture(
                        PermissionRequestResult.reject("agents return text only")));
    }

    private synchronized CopilotClient client() throws Exception {
        if (client == null) {
            CopilotClient started = new CopilotClient();
            started.start().get(timeoutSeconds, TimeUnit.SECONDS);
            var auth = started.getAuthStatus().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!auth.isAuthenticated()) {
                started.close();
                throw new AgentException("Copilot is not signed in; run `copilot` then `/login`, or set GH_TOKEN");
            }
            client = started;
        }
        return client;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}

# Setup

## Prerequisites

- JDK 21 or newer
- Git
- Nothing else. The Maven wrapper (`./mvnw`) is included.
- A GitHub Copilot login only if you want live AI runs. See [live mode](#live-mode-with-copilot).

## 1. Set JAVA_HOME

Do this in every new terminal.

**Windows PowerShell**

```powershell
$env:JAVA_HOME = "C:\path\to\your\jdk"
```

**macOS or Linux**

```bash
export JAVA_HOME=/path/to/your/jdk
```

## 2. Build and test

From the repo root:

```bash
./mvnw verify
```

- You should see `BUILD SUCCESS`.
- 283 orchestrator tests and 162 url-shortener tests run.
- On Windows the exit code may show 1 even on success. Trust the `BUILD SUCCESS` line.

## Run the orchestrator demo

The demo uses a mock AI, so no login is needed. It runs every stage, including a real Maven build.

### Start a run

**Windows PowerShell**

```powershell
./mvnw -pl orchestrator -am package -DskipTests
cd orchestrator
$java = "$env:JAVA_HOME\bin\java.exe"
$jar = "target/orchestrator-0.1.0-SNAPSHOT.jar"
& $java -jar $jar --scenario=demo --change-type=greenfield --request="Build a URL shortener with create, redirect and click counting." --orchestrator.approval.auto-approve=true
```

**macOS or Linux**

```bash
./mvnw -pl orchestrator -am package -DskipTests
cd orchestrator
java="$JAVA_HOME/bin/java"
jar=target/orchestrator-0.1.0-SNAPSHOT.jar
"$java" -jar $jar --scenario=demo --change-type=greenfield \
  --request="Build a URL shortener with create, redirect and click counting." \
  --orchestrator.approval.auto-approve=true
```

- Run orchestrator commands from inside the `orchestrator` folder.
- `auto-approve` approves requirements and design for you. It works only in mock mode.
- Leave it out to approve each gate yourself. The run then pauses and prints the approve command.

### What you should see

```text
Run 5bcdcfe2-277a-424c-918f-7394e587e758 (demo, greenfield): AWAITING_APPROVAL
  requirements       SUCCEEDED
  impact-analysis    SKIPPED
  design             SUCCEEDED
  code               SUCCEEDED
  tests              SUCCEEDED
  build-test         SUCCEEDED
  docs               SUCCEEDED
  policy-scan        SUCCEEDED
  release-readiness  AWAITING_APPROVAL
```

- The long id on the first line is the **run id**. Copy all of it.
- Release always needs a human, so the run stops there.

### Approve the release

**Windows PowerShell**

```powershell
$run = "paste-the-run-id-here"
& $java -jar $jar --approve=${run}:release-readiness --actor=reviewer
```

**macOS or Linux**

```bash
run=paste-the-run-id-here
"$java" -jar $jar --approve=$run:release-readiness --actor=reviewer
```

The last line should read `Run <run-id> (demo, greenfield): SUCCEEDED`.

### Look at the results

| Command | Shows |
|---|---|
| `--metrics=<run-id>` | Success rate, retries, rollbacks, MTTR and latency |
| `--status=<run-id>` | Each stage and its approvals |
| `--lineage=<run-id>` | Output hashes for each stage revision |

- Use them the same way as `--approve`, for example `& $java -jar $jar --metrics=$run`.
- Stage outputs are in `orchestrator/runs/<run-id>/`.
- The audit log is in `orchestrator/runs/audit/<run-id>.jsonl`.
- `git branch --list "agent/*"` shows the run's branch, with one commit per stage.

Lost the run id? List the run folders and pick the newest:

```powershell
Get-ChildItem runs -Directory | Where-Object Name -ne "audit" | Sort-Object LastWriteTime
```

## Run the URL shortener

You need two terminals: one for the server and one to call it.

### Terminal 1: start the server

From the repo root:

```bash
./mvnw -pl url-shortener spring-boot:run
```

Wait for `Started UrlShortenerApplication`. The server runs on `http://localhost:8080`. Press `Ctrl+C` to stop it.

### Terminal 2: try the API

**Windows PowerShell**

```powershell
$link = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/links -ContentType "application/json" -Body '{"url":"https://example.com/hello"}'
$code = $link.code
curl.exe -i -H "User-Agent: Mozilla/5.0" -H "Referer: https://news.example.org/post" http://localhost:8080/$code
Invoke-RestMethod http://localhost:8080/api/v1/links/$code/stats
Invoke-RestMethod http://localhost:8080/api/v1/links/$code/analytics
curl.exe -i http://localhost:8080/zzNoSuch9
```

**macOS or Linux**

```bash
curl -X POST http://localhost:8080/api/v1/links -H 'Content-Type: application/json' -d '{"url":"https://example.com/hello"}'
code=paste-the-code-from-the-response
curl -i -H 'User-Agent: Mozilla/5.0' -H 'Referer: https://news.example.org/post' http://localhost:8080/$code
curl http://localhost:8080/api/v1/links/$code/stats
curl http://localhost:8080/api/v1/links/$code/analytics
curl -i http://localhost:8080/zzNoSuch9
```

### What you should see

| Call | Result |
|---|---|
| Create | A short `code`, `shortUrl` and `expiresAt` 30 days out |
| Visit the code | `302` with `Location: https://example.com/hello` |
| Stats | `clickCount` of 1 |
| Analytics | 1 click, `news.example.org` as top referrer, 1 `BROWSER` |
| Unknown code | `404` |

The full API is in [url-shortener/README.md](../url-shortener/README.md).

## Live mode with Copilot

- Run `copilot login` once.
- Add `--orchestrator.agent.mode=copilot` to **every** command for that run.
- If you forget it on a later command, that command silently uses the mock AI.
- Check each stage's output before you approve it.
- Models per stage are set in `orchestrator/src/main/resources/application.yml`.

## CLI reference

| Command | Does |
|---|---|
| `--scenario=<name> --change-type=greenfield\|brownfield --request=<text>` | Start a run |
| `--approve=<run>:<stage> --actor=<name>` | Approve a stage and continue |
| `--reject=<run>:<stage> --reason=<text> --replan` | Reject a stage and re-run it with the reason |
| `--replan=<run>:<stage> --reason=<text>` | Re-run a stage. Downstream stages go stale. |
| `--resume=<run>` | Continue a paused run |
| `--status=<run>` | Show stages and approvals |
| `--lineage=<run>` | Show output hashes per revision |
| `--metrics[=<run>]` | Show reliability metrics |
| `--export=<run> --to=scenarios/<name>` | Copy evidence after a secret scan |
| `--chaos=<stage>:<count\|always>` | Inject failures to demo recovery |

- Use `greenfield` for new code. It skips impact analysis.
- Use `brownfield` for changes to existing code.
- In PowerShell, wrap multi-word values in single quotes inside the argument, like `--reason='too vague'`. Otherwise the value is cut at the first space.

## Troubleshooting

| Problem | Fix |
|---|---|
| `JAVA_HOME is not defined correctly` | Set `JAVA_HOME` in this terminal (step 1) |
| `java` is not recognized | Use the full path, `& "$env:JAVA_HOME\bin\java.exe"` |
| `unknown run` | Use the full run id, not a part of it |
| `Unexpected token` in PowerShell | Put the command on one line. PowerShell does not use `\` to continue lines. |
| `400 Failed to read request` from `curl.exe` | Use `Invoke-RestMethod` as shown above |
| `Port 8080 was already in use` | Stop the old server, then start again |

To stop an old server on Windows:

```powershell
Get-NetTCPConnection -LocalPort 8080 | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
```

# Verification

## Run without a computer

Push to GitHub and open the **Android APK** workflow. It runs:

```sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :core:testDebugUnitTest :terminal-emulator:test \
  :app:assembleDebug :app:lintDebug
```

The APK is uploaded only when produced; always check that the job is green. Reports and the Room schema are separate artifacts.

## Automated coverage

- OpenAI fragmented tool arguments, tool history and schemas.
- Anthropic text/input-json streaming, usage and grouped tool results.
- Gemini parallel function calls, response names and streaming events.
- Endpoint validation and credential redaction.
- Tokenizer quoting, pipes/redirection, traversal/symlink rejection.
- Actual temporary-file commands, mutation checkpoint callback, unsupported-runtime disclosure.
- Atomic file replacement and rollback tree activation preserving Git metadata.

## Physical-device acceptance checklist

CI compilation and JVM tests do **not** substitute for this checklist:

- [ ] Install artifact on Android 8 and a current Android device; verify cold launch and process-death recovery.
- [ ] Switch English/Persian repeatedly; inspect every screen, dialog, keyboard, back gesture and bottom navigation in RTL.
- [ ] Test system/light/dark themes, 200% font scale, TalkBack, small screens and landscape.
- [ ] Configure each real provider; verify streaming text, tool calls, cancellation, invalid keys, rate limits and airplane mode. Never put real keys in CI logs.
- [ ] Approve/reject an edit; confirm rejected writes do not create files. Edit a file between preview and approval and verify the write is refused.
- [ ] Create, edit, save, import and export files. Test malformed ZIPs, traversal, symlinks, oversized archives and stale editor buffers.
- [ ] Snapshot and restore added/modified/deleted files; verify chats and Git refs remain intact. Interrupt restore in a disposable test workspace and inspect retained backups.
- [ ] Initialize Git, stage/unstage, commit, create/switch branches, inspect working/staged/commit diffs. Use a disposable HTTPS remote for fetch/FF pull/confirmed push.
- [ ] Verify terminal output selection, cancellation, command history, RTL surroundings and LTR commands.
- [ ] Confirm airplane mode still permits local editor, checkpoints, terminal and local Git.
- [ ] Verify secrets are absent from logs, exported ZIPs, backup settings and remote URLs.

Record device/OS, app commit, provider/model and reproduction steps for failures. Do not mark production readiness complete until device, security and release-signing work is done.


## Phase 2 automated validation

Ordinary CI runs app/core/terminal JVM tests, debug and minified release assembly, and Android lint. Live provider tests are skipped unless `-DliveProviders=true` is explicitly passed. MCP MockWebServer tests exercise initialize/session handling, tools discovery and JSON/SSE calls. Terminal tests cover parser chains, actual file effects, per-mutation checkpoints, concurrent tabs, history/cwd isolation, cancellation, quotas and path protection.

To validate a cloud account, configure its key as a protected `provider-validation` environment secret and dispatch **Live provider validation** from main. Select one provider per run. For Ollama on a real phone use the in-app connection test; for public CI provide an HTTPS tunnel you control. Never expose an unauthenticated Ollama server directly to the internet. The workflow does not print keys or response content.

### Live provider configuration

| Provider | Environment secret | Optional variables / inputs |
|----------|--------------------|-----------------------------|
| OpenAI | `OPENAI_API_KEY` | `OPENAI_MODEL` |
| Anthropic | `ANTHROPIC_API_KEY` | `ANTHROPIC_MODEL` |
| Gemini | `GEMINI_API_KEY` | `GEMINI_MODEL` (default `gemini-3.5-flash`), `GEMINI_ENDPOINT` |
| Ollama | `OLLAMA_API_KEY` (required for Ollama Cloud, optional for a self-hosted proxy) | `OLLAMA_ENDPOINT` (`https://ollama.com` for Ollama Cloud, or an HTTPS tunnel to your own server), `OLLAMA_MODEL` (cloud default `gpt-oss:20b`, local default `llama3.2`) |

The workflow resolves `OLLAMA_ENDPOINT` from the dispatch `endpoint` input first, then the `OLLAMA_ENDPOINT` repository variable. An `ollama.com` (or `*.ollama.com`) endpoint always requires `OLLAMA_API_KEY` and sends `Authorization: Bearer <key>`; for any other endpoint the key is optional and is sent only when configured. `http://` is accepted only for loopback and private addresses, where no key is required at all.

Endpoints are normalised before use, so `https://ollama.com`, `https://ollama.com/api` and `https://ollama.com/api/chat` all resolve to the OpenAI-compatible `https://ollama.com/v1/chat/completions`. A `404` usually means an unsupported model: the test prints the models the credential can use (from `GET /v1/models` or the Gemini `models` call) before it fails.

Run the same checks locally with:

```sh
OLLAMA_ENDPOINT=https://ollama.com OLLAMA_API_KEY=... OLLAMA_MODEL=gpt-oss:20b \
  ./gradlew :core:testDebugUnitTest -PliveProviders=true \
  --tests "com.aicode.glass.core.LiveProviderTest.ollama"
```

`AiException` messages now include the HTTP status, the sanitized provider response body, a request id when present and a hint for 400/401/403/404/429/timeouts; set `AICODE_DEBUG_HTTP=true` (the workflow does) to echo the full report to stderr.

The separately dispatched Device smoke validation workflow checks launch/settings and English → Persian → English switching. Hosted API 34 and API 29 attempts timed out before emulator boot; no device-test pass is claimed. Run this workflow on a compatible runner or run the instrumentation tests on a physical device. Android 34 device validation remains a release gate. It is not a full RTL, accessibility, provider-account or signed-release certification.

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

The push-build emulator job installs the debug app and checks launch/settings and English → Persian → English switching on Android 34. It is not a full RTL, accessibility, provider-account or signed-release certification.

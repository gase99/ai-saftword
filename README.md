# AiCode Glass

**A local-first, bilingual AI coding workspace for Android.**

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · Coroutines/Flow · OkHttp SSE · JGit

> This is a functional first release, not a claim of audited production readiness. The APK produced by CI is a **debug APK**. No Linux container, SSH backend, or mobile Gradle/Python/Node runtime is bundled.

## Download without a computer

1. Open the [Android APK workflow](https://github.com/gase99/ai-saftword/actions/workflows/android.yml).
2. Select the newest **green** run on the pull-request branch.
3. Under **Artifacts**, download `AiCode-Glass-debug-<commit>`.
4. Extract the ZIP on your phone and open `app-debug.apk`.
5. Allow installation from that browser/file manager when Android asks. This is a development build, not a Play Store release.

Every push runs unit tests, Android lint, and a debug APK build. A failed run is not an APK release. The workflow also uploads test/lint reports and the generated Room schema. You can also trigger a build with **Run workflow**.

## What works

- **AI hub:** OpenAI, Anthropic Messages, Gemini streaming function calls, and custom OpenAI-compatible HTTPS endpoints. Configurable model IDs. Incremental Markdown and fenced-code syntax coloring. Bounded multi-round tool loop with visible results, cancellation, token usage when supplied, and persisted conversations.
- **Guarded tools:** list/read/search workspace files and read Git status/diffs. File creation/replacement and exact text replacement require an explicit approval dialog. Ask mode exposes only read tools. No AI shell execution or automatic remote push.
- **Workspace:** multiple private workspaces, file browser, UTF-8 editor with syntax coloring and stale-save detection, ZIP import/export through Android's document picker.
- **Checkpoints:** SHA-256 content-addressed file snapshots before AI edits, editor saves, terminal mutations, imports, branch switches, and rollback. Thirty snapshots retained per workspace. Rollback is itself reversible through a safety snapshot.
- **Git:** real JGit initialization, staged/unstaged status, staging, commits, branch creation/switching, 50-entry history, file and commit diffs. HTTPS origin configuration, fetch, fast-forward-only pull, and explicitly confirmed non-force push. Optional encrypted GitHub token.
- **Terminal:** separate JVM execution module and Compose rendering module. Real sandboxed file operations, quoting, pipelines, output redirection, command history, cancellation and timeout. Read-only Git bridge. Explicitly identifies unsupported runtime commands instead of fabricating output.
- **Liquid Glass UI:** translucent layered cards, gradient borders/backgrounds, API 31+ background blur with gradient fallback, dark/light/system themes, edge-to-edge layout.
- **English / Persian:** immediate in-app language switching, localized resources and explicit `CompositionLocalProvider(LocalLayoutDirection provides …)`; code and terminal remain LTR.

## First use

1. Settings → choose your language/theme.
2. Choose a provider, enter a model ID and your own API key, then **Save connection**. For Custom, enter the HTTPS base URL up to `/v1`, without `/chat/completions`.
3. Chat → choose **Build** to allow approval-gated edits or **Ask** for read-only help.
4. Send a prompt. File reads are sent to the chosen provider as tool results. Review every write before approving.
5. Terminal → Files to edit/import/export; Checkpoints to snapshot/restore.
6. Git → initialize, stage, and commit. Set author identity in Settings. Configure origin in Git and a fine-grained token in Settings if needed.

No API key is bundled. Provider usage may incur charges. Model availability and API compatibility depend on your account/provider; model IDs are editable rather than hard-coded promises.

## Phase 2

Phase 2 adds HTTP MCP tool discovery with per-call approval, three parallel read-only analysts, encrypted multi-key rotation and opt-in fallback, Ollama, interactive multi-tab terminal sessions, localized terminal help, optional metadata audit export, reduced-motion glass effects, and protected tag-only signed APK/AAB releases.

See [Phase 2 scope and validation](docs/PHASE2.md), [live-provider validation](docs/TESTING.md), and [production signing](docs/PRODUCTION_RELEASE.md). Mock protocol tests are not represented as live account validation. The terminal remains a safe local file/Git shell, not a Linux/PTY runtime.

## Architecture

```text
app/
  agent/             bounded tool loop, approvals, workspace tool execution
  data/              workspace, checkpoints, chat, JGit repositories
  ui/model/          lifecycle-aware MVVM state holders
  ui/screens/        Chat, Terminal/Files/Editor/Checkpoints, Git, Settings
  ui/components/     Markdown, syntax highlighting, common glass components
  ui/theme/          Material 3 themes and layered glass surfaces
core/
  data/              Room entities/DAOs/database and DataStore preferences
  network/           provider codecs, normalized events, SSE transport
  security/          Android Keystore AES-GCM secret storage
  di/                Hilt bindings
terminal-emulator/   pure Kotlin/JVM sandbox shell and parser
terminal-view/       Compose terminal output renderer
```

The supplied foundation IDs, SDK levels, dependency baseline and bilingual tab resources are retained. Java 17 targets, additional dependencies, test configuration and Hilt's published-plugin resolution were added to make that foundation build reproducibly.

## Security and limits

- API keys and Git tokens are encrypted with a non-exportable Android Keystore key. App backup is disabled. Secrets are not embedded in Git URLs or exports. There is no app-operated backend or analytics.
- Prompts, selected conversation history and tool results go directly to the selected provider. Workspace content is **not** encrypted against a compromised rooted device. Do not store production secrets in workspace files.
- Paths reject traversal, absolute editor paths, Git metadata edits and symlinks. ZIP imports reject traversal/symlinks and enforce size/count limits. Imports replace files, preserving `.git` and creating a pre-import checkpoint.
- Workspace limits: 1 MB per file, 64 MB total, 4,000 files. Checkpoints exclude `.git`; restoring does not rewind commits, branches, chats or credentials. Export important data before uninstalling.
- One agent turn runs at a time, with at most six provider rounds and twelve tool calls. History is bounded. Requests are not durable background jobs: keep the app open; process death interrupts them. Interrupted messages/tools are marked on restart and never silently replayed.
- The terminal is **not** Termux, Alpine, a PTY or an SSH client. It cannot run native binaries, shell scripts, package managers or builds. Use the included GitHub Actions workflow for cloud APK builds.
- Git pull requires a clean worktree and is fast-forward only. Push never uses force. Branch switching requires a clean worktree. Full merge-conflict resolution and repository cloning UI are not included in this version.
- The debug APK is installable but not a signed production release. Before distribution, add protected release signing, dependency/security review, device/instrumentation testing, accessibility testing, provider-contract integration tests, crash reporting consent, and store-policy/target-SDK review.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/TESTING.md](docs/TESTING.md), and [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

## Inspiration

Inspired by the mobile workspace concepts in `hwsyyds666/Aicode-Glass`. This implementation is independent: no upstream source, assets, terminal binaries, or container rootfs were copied. Upstream's larger Linux/SSH feature set is not represented as implemented here; Phase 2 MCP is the documented HTTP subset.

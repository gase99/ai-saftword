# Architecture and safety contracts

## Dependency direction

`app` owns Android presentation and orchestration. `core` owns persistence, preferences, secrets, and normalized provider networking. `terminal-emulator` is a pure Kotlin/JVM module with no Android dependency. `terminal-view` renders emulator state using Compose. The app adapts Git and checkpoint services to the terminal; the terminal cannot spawn processes.

## AI request lifecycle

1. The selected conversation and workspace are captured when a turn starts.
2. The user message is persisted before network I/O.
3. The provider codec translates normalized messages and JSON tool schemas to its wire protocol.
4. OkHttp SSE emits normalized text, tool calls and optional usage. The coordinator throttles Room writes while updating the UI incrementally.
5. Complete tool calls are validated and persisted. Read tools execute locally. Each write shows its target and proposed content and waits for explicit approval.
6. Immediately before a write, the tool re-reads the file and compares it to the approved preview. A changed file invalidates the approval. Under the workspace lock, it records a content-addressed checkpoint and atomically writes the file.
7. Tool results are persisted and sent back to the provider, with bounded rounds and tool count. Cancellation stops collection and closes the SSE source; partial text is retained as interrupted.

Provider keys are read from device-bound encrypted storage only when needed. HTTP errors are surfaced, not replaced by fake assistant responses. Redirect following is disabled to avoid forwarding credentials to another origin. Custom endpoints require HTTPS without user info, query or fragment.

## Consistency and rollback

All workspace mutations use a per-workspace coroutine mutex. File replacement uses a same-directory temporary file and atomic rename where supported. A snapshot consists of Room manifest rows and immutable SHA-256 blob files. Snapshot retention and orphan collection run under a shared snapshot mutex.

Restore first verifies every blob hash, materializes a staging tree, records a safety snapshot, renames the old tree to a backup, activates the staging tree, then preserves `.git`. Activation failure attempts to restore the backup. This is not a power-loss-atomic filesystem transaction; an interrupted rename may leave a retained backup. Database/blob storage is not a journaled cross-store transaction. These limitations must be addressed before claiming audited durability.

## Deliberate boundaries

No arbitrary tool registry, external executable, SSH credential flow, MCP connection or autonomous Git mutation exists. Tool names are an allowlist, argument schemas are checked, paths stay inside the selected private workspace, and Ask mode removes write tools as well as enforcing the restriction at execution time.

The shell implements a documented subset rather than pretending to be POSIX. It supports built-in commands, quotes, pipes and redirection; it rejects expansion and command separators. It is useful for local file workflows, not for running the projects it edits.

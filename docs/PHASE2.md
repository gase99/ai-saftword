# Phase 2 implementation and validation boundary

## MCP

Settings accepts up to four explicitly configured MCP Streamable HTTP servers. The client performs `initialize`, the initialized notification, paginated `tools/list`, and approved `tools/call`. It handles JSON and SSE response bodies, negotiated protocol headers, session IDs, bounded responses, optional encrypted bearer tokens, and conservative argument-schema checks. Tool calls are exposed to the main model using namespaced names and visible result cards.

This is **HTTP MCP**, not desktop stdio process launching. Android app sandboxing does not provide a bundled Python/Node/Linux runtime for arbitrary MCP server executables. A server can run on a LAN machine, a loopback port forward, or an HTTPS endpoint. Emulator loopback commonly uses `10.0.2.2`; a physical phone's `127.0.0.1` refers to the phone itself.

Remote HTTP is rejected. Local HTTP requires an explicit toggle and a private/loopback IP literal or localhost. The shared OkHttp interceptor rejects other cleartext requests and URL credentials. This is not a substitute for trusting the MCP server: external tools may have their own filesystem, network or account access. Every call requires user approval; Ask mode does not advertise or execute them. The client does not implement OAuth, sampling, roots, resources/prompts UI, binary results, server notifications, or stdio transports.

## Parallel specialists

The Chat analysis action and `analyze_parallel` tool run architecture, security and test specialists concurrently, capped at three. They receive a bounded, read-only workspace snapshot; they cannot call tools, write files, execute terminal commands or recursively spawn agents. The UI remains interactive and exposes progress, partial output, errors and cancellation. These are process-local coroutines, **not durable foreground services**. Closing the app/process can interrupt them. `.env` and secret-named files are excluded, but users must still avoid placing secrets in source sent to a provider.

## Providers and unreliable networks

- OpenAI, Anthropic, Gemini, custom OpenAI-compatible and Ollama OpenAI-compatible endpoints.
- Ollama requires no fake API key. Configure a pulled model and the reachable `/v1` base URL.
- Up to eight encrypted keys per provider, preserving the Phase 1 single-key format.
- Bounded exponential backoff with jitter for connection errors, 408/409/429 and selected 5xx responses.
- Key rotation on authentication/rate-limit failures; 60-second in-process rate-limit cooldown.
- Cross-provider fallback is **off by default**, with explicit provider selection and a privacy warning.
- No automatic replay after text/tool output begins: a partial stream fails visibly rather than duplicating output or side effects.

Automated MockWebServer tests validate the app's HTTP/SSE behavior, including fragmented provider tools, malformed/empty streams, midstream failure, retry-before-output, key rotation, opt-in fallback and keyless Ollama requests. These are **contract simulations**, not proof of a real account's availability.

`LiveProviderTest` and the manually dispatched **Live provider validation** workflow send an actual small request only when explicitly enabled and credentials are configured. No provider account secrets were supplied for this implementation, so cloud-provider live success is not claimed. A public GitHub runner cannot reach a user's phone/LAN Ollama server without a deliberately configured secure tunnel. The in-app live test runs on the device and can reach the user's actual local endpoint.

## Terminal 2.0

The pure JVM engine has an explicit parser for quotes, filename globs, newlines/semicolons, `&&`, `||`, pipes, stdout/stderr redirects and append. It implements local file/text commands and local JGit shortcuts. Mutating pipelines request a checkpoint before execution while the host owns the workspace lock. Up to four sessions have independent cwd, history, transcript and cancellation; file mutations remain serialized across sessions, AI and editor operations.

The Compose renderer supports hardware Up/Down history, accessible on-screen history controls for soft keyboards, bounded selectable output, per-session scroll state, distinct input/stdout/stderr colors and localized help/common status messages. Code and paths remain LTR inside Persian RTL chrome. File operations cannot traverse the workspace, edit `.git` directly or follow symlinks. Workspace quotas are checked after commands; a quota error leaves the pre-command snapshot available for recovery, rather than pretending the entire shell command was an atomic transaction.

Optional audit logging stores only the last 500 command names, session IDs, timestamps, exit codes and durations. It does not store arguments or file contents. Export is user-initiated through Android's document picker. Native binaries, shell expansion, interactive curses/PTY programs and package installation remain unsupported and are reported as errors.

## Release and UI

Branch CI compiles both debug and R8-minified release. Tag-only `v*` release automation requires a protected production environment and signing secrets, builds signed APK/AAB, verifies signatures, publishes checksums and preserves the R8 mapping. See `PRODUCTION_RELEASE.md`.

Glass surfaces retain API-level-aware blur, add slow gradient-orb motion and honor a reduced-motion preference. Layout direction is supplied explicitly at the app root, with LTR scoped to code/terminal content. English/Persian resource parity is checked. CI includes an Android 34 launch/settings/language-switch smoke test after successful push builds. RTL/device typography, TalkBack and font-scale acceptance still require the physical-device checklist.

## Honest remaining gates

- Configure provider-validation secrets and run each real provider, including streaming tool calls and account-specific rate limits.
- Run a trusted external MCP server interoperability test and reject/approve/cancel calls on a phone.
- Configure production signing secrets and approve a tag release; no production APK/AAB is claimed signed until that workflow succeeds.
- Complete physical-device RTL/accessibility and signed-release smoke tests.
- The existing Room database remains schema version 1. Phase 2 stores new preferences/MCP configuration in DataStore and introduces no destructive migration.

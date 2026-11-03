# Signed production releases

Phase 2 release builds use R8 shrinking and resource shrinking. Ordinary branch/PR builds still produce a debug APK and **do not need signing secrets**. An unsigned local release can be compiled, but the tag workflow refuses to publish without signing.

## One-time setup (GitHub website)

1. Generate and securely back up an upload/release keystore using your trusted signing tool. Never commit it or paste its passwords into issues or chat.
2. In repository Settings → Environments, create `production`. Restrict deployments to protected tags `v*`; require a reviewer if available.
3. Add these **environment secrets** to production (not plaintext repository variables):
   - `ANDROID_KEYSTORE_BASE64`: the binary keystore encoded as base64
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. Protect the private key and maintain a recovery backup. Losing it prevents compatible upgrades under the same signing identity.
5. Complete `RELEASE_CHECKLIST.md`, including physical-device tests, privacy policy, current store target-SDK requirements and release notes. The requested API 34 baseline is retained, not asserted to meet future Play Store requirements.

## Publish

After the Phase 2 PR is approved and merged into `main`, create a stable tag such as `v2.0.0` pointing to that merged commit. The **Signed production release** workflow runs only on `v*` tags, verifies the commit belongs to `main`, tests, builds an APK/AAB, verifies both signatures, uploads checksums and R8 mapping, then creates a GitHub release.

Version code is `major * 1,000,000 + minor * 1,000 + patch`; each component must fit the documented bounds. It is monotonic for normal semantic versions. Never reuse a version code or change signing identity for an existing application ID.

The workflow uses an ephemeral runner temp file with restricted permissions, masks secret values through GitHub's secret mechanism, never prints the keystore, and removes the transient file afterward. It does not accept pull-request signing requests. `production` environment approval is an additional human gate.

## R8 validation

CI compiles the minified release, not just debug. ProGuard rules retain JGit integrations and required serialization metadata. Before distribution, smoke-test the **signed release artifact**, particularly Hilt injection, Room migrations, SSE, JGit, MCP JSON serialization and Compose navigation. Debug success alone is not release validation.

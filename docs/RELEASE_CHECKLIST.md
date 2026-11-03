# Production release gate

The CI artifact is a debug build. This checklist is intentionally not marked complete.

- [ ] Choose a license and document third-party notices.
- [ ] Complete physical-device and accessibility acceptance tests.
- [x] Add Compose launch/language-switch instrumentation and a manual emulator CI workflow.
- [ ] Obtain a passing device smoke result (hosted emulator attempts timed out before boot).
- [ ] Add real-provider contract tests using disposable test accounts and protected secrets.
- [ ] Review all dependencies, Gradle wrapper checksum, action pinning and Android permissions.
- [ ] Add release signing using protected GitHub environment secrets; never commit a keystore or password.
- [ ] Validate minified release behavior, JGit reflection/service loading, Room and Hilt.
- [ ] Review current Google Play target SDK and data-safety requirements at release time; the requested foundation currently targets API 34.
- [ ] Define upgrade migrations using the committed Room schema; test every supported upgrade path.
- [ ] Decide whether durable foreground execution, Linux/SSH, clone and conflict-resolution UI are required; none is silently supplied by this release.
- [ ] Add a privacy policy, support channel and opt-in crash reporting if needed.
- [ ] Review checkpoint disk quotas, interrupted filesystem recovery and key-loss behavior.
- [ ] Obtain user approval of the pull request before merging.

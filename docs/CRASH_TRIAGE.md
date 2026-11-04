# Startup crash triage

The APK produced by CI compiles and passes every JVM test, yet an installed device can still
force-close immediately. That kind of failure almost always comes from **state left on the device
by a previous install**, not from the build. This page lists the two known classes, what the app
now does about them, and how to capture the real cause.

## 1. Capture the real stack trace (30 seconds)

Connect the phone with USB debugging enabled and start the app:

```sh
adb logcat -c                              # clear the buffer first
adb logcat -b crash                        # crash buffer only
# or, while the app starts:
adb logcat -s AiCodeGlassCrash:V AndroidRuntime:E
```

Every uncaught exception is now logged with the stable tag `AiCodeGlassCrash`
(`AiCodeApplication.kt`), so the trace can be filtered out of the noise.

Useful extras:

```sh
adb shell dumpsys package com.aicode.glass | grep versionName    # what is actually installed
adb shell run-as com.aicode.glass ls -l databases files/datastore
adb shell run-as com.aicode.glass ls -l files/workspaces
```

## 2. Local database (Room)

Symptom in the trace:

```
java.lang.IllegalStateException: Room cannot verify the data integrity. Looks like you've
changed schema but forgot to update the version number.
```

Cause: the app ships `@Database(version = 1)` with `exportSchema = true` and no migrations. When a
database file written by an older build has the same version but a different identity hash, Room
aborts. Note that `fallbackToDestructiveMigration()` alone does **not** help here - Room only runs
migrations when the stored version differs from the compiled one.

What the app does now:

* `GlassDatabase` is at **version 2** (see the version history comment in the file).
* `DatabaseModule` builds it with `.fallbackToDestructiveMigration()`, so a version 1 file (or any
  future downgrade) is dropped and recreated instead of crashing.
* Room flows are wrapped in `catch` in `ChatRepository`, `WorkspaceRepository` and
  `CheckpointRepository`, and `AppViewModel` shows the error instead of spinning forever. A query
  failure can no longer kill the process.

Consequence: upgrading from a build with the old schema **discards local chats, checkpoints and
settings**. Workspace files on disk are untouched.

Before a store release, replace the fallback with explicit migrations:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'complete'")
        // ... one statement per change between the two exported schemas
    }
}
// DatabaseModule:
.addMigrations(MIGRATION_1_2)
```

The exported schemas live in `core/build/generated/room-schemas/` (uploaded by the **Android APK**
workflow); commit them to `core/schemas/` if you want migration tooling to verify DDL automatically.

To wipe the device database by hand:

```sh
adb shell pm clear com.aicode.glass          # removes database, settings and workspace files
# or
adb uninstall com.aicode.glass
```

## 3. Settings storage (DataStore)

Symptom in the trace:

```
androidx.datastore.core.CorruptionException: Unable to read proto/preferences file
```

`AppViewModel` collects the settings flow with `SharingStarted.Eagerly`, so a half-written
`files/datastore/settings.preferences_pb` used to crash the process during `onCreate` - i.e. an
immediate force close with no UI at all.

What the app does now:

* `preferencesDataStore(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })`
  resets an unreadable settings file instead of throwing.
* `SettingsRepository.preferences` catches IO errors and falls back to defaults, so no consumer can
  observe a broken flow. API keys are not affected: they live in the device-bound keystore store,
  not in DataStore.

## 4. Network client initialisation

`StreamingAiClient`, `ResilientAiClient`, `ProviderUrls` and `HttpClients` are plain objects:

* `HttpClients.provider()` only configures timeouts and the local-HTTP interceptor. It performs no
  DNS lookup, no socket, no disk access and cannot throw for the shipped configuration.
* `StreamingAiClient.stream()` is where every validation, URL resolution and the HTTP call happen,
  and only when the user sends a message.
* Hilt instantiates the graph lazily, so nothing is created during `Application.onCreate`.

`HttpClientsTest` pins this contract (timeouts, no connection opened at construction, client chain
constructed without throwing).

## 5. If the trace points somewhere else

Attach the `adb logcat -b crash` output to the issue. The first `Caused by:` line is what matters;
`ActivityThread`/`Instrumentation` frames above it are boilerplate
(`Unable to start activity ComponentInfo{com.aicode.glass/...}` just means "onCreate threw").

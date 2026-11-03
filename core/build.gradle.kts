plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
}
android {
    namespace = "com.aicode.glass.core"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Room exports one JSON file per database version into room.schemaLocation, and every KSP variant
// task (debug, release and the unit-test variants) writes to that same path. When one of those
// tasks finds a leftover or half-written file it aborts the whole build with
// "java.lang.IllegalStateException: Empty schema file" (SchemaBundle.deserialize). Start every
// KSP execution from an empty directory: the exported content is identical for every variant of
// the same database version.
val roomSchemaDirectory = layout.buildDirectory.dir("generated/room-schemas")
ksp { arg("room.schemaLocation", roomSchemaDirectory.get().asFile.absolutePath) }
tasks.matching { it.name.startsWith("ksp") }.configureEach {
    doFirst {
        val directory = roomSchemaDirectory.get().asFile
        if (directory.exists()) directory.deleteRecursively()
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    api("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// Environment variables consumed by LiveProviderTest. Every credential/model/endpoint override
// must be listed here or the Gradle test JVM will not see it (this is why OLLAMA_API_KEY used to
// vanish even when the workflow exported it).
val liveProviderEnvironment = listOf(
    "OPENAI_API_KEY", "OPENAI_MODEL", "OPENAI_ENDPOINT",
    "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL", "ANTHROPIC_ENDPOINT",
    "GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_ENDPOINT",
    "OLLAMA_API_KEY", "OLLAMA_MODEL", "OLLAMA_ENDPOINT",
    "AICODE_DEBUG_HTTP"
)

tasks.withType<Test>().configureEach {
    // Accept -PliveProviders=true, -DliveProviders=true or ORG_GRADLE_PROJECT_liveProviders=true.
    val liveEnabled = (findProperty("liveProviders") ?: System.getProperty("liveProviders"))
        ?.toString()?.toBoolean() ?: false
    systemProperty("liveProviders", liveEnabled.toString())
    liveProviderEnvironment.forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    // Provider failures must be readable in the CI log, not buried in an HTML report.
    testLogging {
        // Valid TestLogEvent names: started, passed, skipped, failed, standardOut, standardError.
        events("started", "passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}

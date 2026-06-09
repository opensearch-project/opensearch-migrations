/*
 * :tui:release — release-artifact assembly.
 *
 * What ships per release:
 *
 *   migration-tui-<target>          one binary per arch (consumed from :tui)
 *                                   Windows binary has .exe suffix.
 *   migration-assistant-bundle.tar.gz   helm/cfn/values/skills/manifest
 *   install.sh                      POSIX sh, version-stamped (linux + macOS)
 *   install.ps1                     PowerShell, version-stamped (Windows)
 *   VERSION.txt                     plain text, just the version
 *
 * This file produces all five into build/dist/<version>/. The CI release
 * job uploads them to the GitHub Release; nothing in here pushes
 * anything anywhere.
 *
 * Tasks:
 *   stampInstallScript        install.sh.template  → install.sh, with @VERSION@ replaced
 *   stampInstallScriptWindows install.ps1.template → install.ps1, with @VERSION@ replaced
 *   writeVersionFile          write VERSION.txt
 *   assembleBundle            tar -czf migration-assistant-bundle.tar.gz of bundle/
 *   stageBinary               copy the binary from :tui's `tuiBinary` configuration
 *                             into build/dist/<version>/migration-tui-<classifier>
 *                             (with .exe suffix on windows targets)
 *   assemble                  depends on all five
 *
 * Cross-target story: the binary is matrix-built in CI. Each matrix
 * entry runs `./gradlew :tui:release:assemble -Ptarget=<triple>` and
 * uploads the resulting build/dist/ contents. The release-collator job
 * downloads all matrix outputs and uploads them together.
 */

import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Compression

plugins {
    base
}

// ─── version: inherit from :tui ───────────────────────────────────────
//
// :tui sets its project.version from -PtuiVersion (or "0.0.0-dev").
// We mirror that, so distDir, install.sh stamping, VERSION.txt, etc.
// all line up across the two projects.
project.version = project(":tui").version

// ─── consume the binary from :tui ─────────────────────────────────────
//
// `:tui` exposes its compiled binary via the `tuiBinary` Configuration.
// We declare a matching resolvable configuration here.
val tuiBinary by configurations.registering {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "release-binary"))
    }
}

dependencies {
    add(tuiBinary.name, project(mapOf("path" to ":tui", "configuration" to "tuiBinary")))
}

// ─── output directory ─────────────────────────────────────────────────
val distDir: Provider<Directory> = layout.buildDirectory.dir("dist/${project.version}")

// ─── 1. stamp install.sh ──────────────────────────────────────────────
val stampInstallScript = tasks.register<Copy>("stampInstallScript") {
    group = "release"
    description = "Substitute @VERSION@ in install.sh.template → build/dist/<version>/install.sh"

    from(layout.projectDirectory.file("install.sh.template")) {
        rename { "install.sh" }
        filter { line -> line.replace("@VERSION@", project.version.toString()) }
    }
    into(distDir)

    doLast {
        // Gradle's Copy task doesn't preserve exec bits across rename+filter,
        // so we set the bit explicitly. POSIX-only; the action only runs on
        // unix-y CI runners (linux/macos), never windows.
        distDir.get().file("install.sh").asFile.setExecutable(true, false)
    }
}

// ─── 1b. stamp install.ps1 ────────────────────────────────────────────
val stampInstallScriptWindows = tasks.register<Copy>("stampInstallScriptWindows") {
    group = "release"
    description = "Substitute @VERSION@ in install.ps1.template → build/dist/<version>/install.ps1"

    from(layout.projectDirectory.file("install.ps1.template")) {
        rename { "install.ps1" }
        filter { line -> line.replace("@VERSION@", project.version.toString()) }
    }
    into(distDir)
    // No exec-bit on Windows; PowerShell scripts don't need it.
}

// ─── 2. VERSION.txt ───────────────────────────────────────────────────
val writeVersionFile = tasks.register("writeVersionFile") {
    group = "release"
    description = "Write build/dist/<version>/VERSION.txt"

    val output = distDir.map { it.file("VERSION.txt") }
    val versionStr = project.version.toString()
    inputs.property("version", versionStr)
    outputs.file(output)

    doLast {
        output.get().asFile.parentFile.mkdirs()
        output.get().asFile.writeText("$versionStr\n")
    }
}

// ─── 3. bundle.tar.gz ─────────────────────────────────────────────────
//
// The bundle/ directory is the source tree. In the monorepo, the helm/
// cfn/ values/ skills/ subdirs are populated by sibling subprojects
// (e.g. :deployment:helm) emitting into bundle/ via their own copy
// tasks. For the POC, bundle/ is whatever exists in the source tree.
val assembleBundle = tasks.register<Tar>("assembleBundle") {
    group = "release"
    description = "Tar+gzip the assistant bundle into build/dist/<version>/migration-assistant-bundle.tar.gz"

    archiveBaseName.set("migration-assistant-bundle")
    // No version suffix on the bundle filename. The version is injected
    // into install.sh and VERSION.txt at stamp time; the tarball stays
    // version-less so install.sh can reference a stable filename in the
    // GitHub Release URL.
    archiveVersion.set("")
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(distDir)

    from(layout.projectDirectory.dir("bundle"))
    // No leading directory inside the tarball; install.sh extracts
    // directly into ~/.opensearch-migration-assistant/.
    into("")
}

// ─── 4. stage the binary ──────────────────────────────────────────────
//
// We copy from the resolved `tuiBinary` configuration into
// build/dist/<version>/migration-tui-<target>. The classifier on the
// :tui artifact is the target triple ("linux-aarch64", "host", etc.).
val stageBinary = tasks.register<Copy>("stageBinary") {
    group = "release"
    description = "Copy migration-tui binary from :tui into build/dist/<version>/"

    from(tuiBinary)
    into(distDir)
    rename { _ ->
        // Configuration artifacts arrive as <name>-<classifier>.<ext>; we
        // strip the extension if blank and end up with e.g.
        // "migration-tui-linux-aarch64" or "migration-tui-host".
        // Windows targets get .exe appended so the artifact runs natively.
        val target = (findProperty("target") as String?)?.takeIf { it.isNotBlank() } ?: "host"
        val suffix = if (target.contains("windows")) ".exe" else ""
        "migration-tui-$target$suffix"
    }

    doLast {
        // Same exec-bit dance as install.sh.
        distDir.get().asFile.listFiles { f -> f.name.startsWith("migration-tui-") }
            ?.forEach { it.setExecutable(true, false) }
    }
}

// ─── lifecycle ────────────────────────────────────────────────────────
tasks.named("assemble") {
    dependsOn(stampInstallScript, stampInstallScriptWindows, writeVersionFile, assembleBundle, stageBinary)
}

// ─── outgoing artifacts ───────────────────────────────────────────────
//
// Anything that wants the per-version dist tree (e.g. a future
// `:tui:release:publish` task that uploads to GitHub Releases, or the
// CI workflow grabbing files for upload-artifact) consumes from this.
val releaseArtifacts by configurations.registering {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(releaseArtifacts.name, distDir) {
        builtBy(stampInstallScript, stampInstallScriptWindows, writeVersionFile, assembleBundle, stageBinary)
    }
}

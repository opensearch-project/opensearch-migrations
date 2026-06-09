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

// ─── consume the agent-skills tarball from :agent-skills ──────────────
//
// `:agent-skills` exposes its bundled skill tree (Startup.md + manifest +
// SKILL.md dirs + agent-sops/) via the `agentSkills` Configuration as
// agent-skills.tar.gz. We extract it into the bundle staging area under
// skills/ so the migration-tui ships the same skill content the CLI
// already consumes — single source of truth, no duplication of file
// lists.
val agentSkillsTarball by configurations.registering {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    add(agentSkillsTarball.name, project(mapOf("path" to ":agent-skills", "configuration" to "agentSkills")))
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
// Bundle layout in the published tarball:
//   helm/migrationAssistantWithArgo/   the deployment chart (`helm install`)
//   samples/                            sample workflow YAMLs (TUI ConfigStore::list_samples)
//   manifest.yaml                       schema-v1 manifest of what's bundled
//   README.md                           how to consume each piece
//
// `tui/release/bundle/` carries only README.md (the human-readable spec).
// helm/ + samples/ are STAGED at build time by stageBundle from authoritative
// monorepo locations, so a single source-of-truth can't drift.
val bundleStageDir = layout.buildDirectory.dir("bundle-staged")

// Helm chart source: deployment/k8s/charts/aggregates/migrationAssistantWithArgo
val helmSource = rootProject.layout.projectDirectory
    .dir("deployment/k8s/charts/aggregates/migrationAssistantWithArgo")

// Samples source: orchestrationSpecs/.../scripts/samples
val samplesSource = rootProject.layout.projectDirectory
    .dir("orchestrationSpecs/packages/config-processor/scripts/samples")

val stageBundle = tasks.register<Copy>("stageBundle") {
    group = "release"
    description = "Stage helm chart + samples + skills + README into build/bundle-staged/"

    // Ensure :agent-skills produces its tarball before we try to extract it.
    // The configuration→artifact wiring exposes the file but doesn't add an
    // implicit task dependency on a Copy task's `from(tarTree(...))`.
    dependsOn(":agent-skills:agentSkillsTar")

    // Authoritative spec/README from the source tree.
    from(layout.projectDirectory.dir("bundle"))
    // Helm chart, preserving its directory name so `helm install ./helm/migrationAssistantWithArgo` works.
    from(helmSource) { into("helm/migrationAssistantWithArgo") }
    // Workflow samples, flat under samples/.
    from(samplesSource) { into("samples") }
    // Agent skills extracted from :agent-skills's agent-skills.tar.gz.
    // The tarball already wraps everything in skills/, so we extract its
    // tree as-is — output lands at bundle-staged/skills/.
    from(agentSkillsTarball.map { tarTree(it.singleFile) })

    into(bundleStageDir)

    doLast {
        // Generate manifest.yaml describing what's bundled. Written last so
        // it captures exactly what stageBundle assembled.
        val manifest = bundleStageDir.get().file("manifest.yaml").asFile
        val ver = project.version.toString()
        manifest.writeText(
            """
            |# migration-assistant bundle manifest
            |schema_version: 1
            |bundle_version: "$ver"
            |contents:
            |  helm:
            |    path: helm/migrationAssistantWithArgo
            |    description: Helm chart for the migration-assistant deployment.
            |    values_files:
            |      - helm/migrationAssistantWithArgo/values.yaml
            |      - helm/migrationAssistantWithArgo/valuesEks.yaml
            |      - helm/migrationAssistantWithArgo/valuesForLocalK8s.yaml
            |      - helm/migrationAssistantWithArgo/valuesForLocalK8sWithEnvSubst.yaml
            |  samples:
            |    path: samples
            |    description: Sample workflow YAMLs (consumed by migration-tui ConfigStore::list_samples).
            |  skills:
            |    path: skills
            |    description: Agent skills (Startup.md, manifest.json, SKILL.md trees, agent-sops). Source of truth = :agent-skills subproject.
            |""".trimMargin()
        )
    }
}

val assembleBundle = tasks.register<Tar>("assembleBundle") {
    group = "release"
    description = "Tar+gzip the staged assistant bundle into build/dist/<version>/migration-assistant-bundle.tar.gz"

    dependsOn(stageBundle)

    archiveBaseName.set("migration-assistant-bundle")
    // No version suffix on the bundle filename. The version is injected
    // into install.sh and VERSION.txt at stamp time; the tarball stays
    // version-less so install.sh can reference a stable filename in the
    // GitHub Release URL.
    archiveVersion.set("")
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(distDir)

    from(bundleStageDir)
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

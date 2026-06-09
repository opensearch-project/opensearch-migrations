/*
 * :tui — the Rust migration-tui crate, wrapped in Gradle.
 *
 * Why Gradle around cargo?
 *   - The opensearch-migrations monorepo is Gradle-native.
 *   - CI orchestration (release pipeline, drift checks, version
 *     consistency) is already plumbed through Gradle tasks.
 *   - This file is the seam: above it, everything is Gradle. Below it,
 *     everything is cargo.
 *
 * Why Exec tasks instead of a Rust Gradle plugin?
 *   - Zero plugin debt. The monorepo doesn't ship a third-party Rust
 *     plugin today; this file inherits no surprises.
 *   - Total control over inputs/outputs, which Gradle uses for
 *     incremental-build dependency tracking.
 *   - cargo already does perfect incremental builds; Gradle just needs
 *     to know which files trigger a rebuild.
 *
 * Tasks in this file:
 *   versionCheck  — confirm Cargo.toml's package.version is the perma-
 *                   placeholder '0.0.0-dev'. Real version comes from
 *                   the -PtuiVersion Gradle property at build time.
 *   cargoTest     — cargo test --all-targets
 *   cargoClippy   — cargo clippy --all-targets -- -D warnings
 *   cargoBuild    — cargo build --release [--target <triple>]
 *   check         — depends on versionCheck + cargoTest + cargoClippy
 *   assemble      — depends on cargoBuild
 *
 * The compiled binary path is exposed via the `tuiBinary` Configuration
 * so that :tui:release can consume it without reaching into target/.
 */

import org.gradle.api.tasks.Exec
import java.nio.file.Files

plugins {
    base
}

// ─── target plumbing ──────────────────────────────────────────────────
//
// `target` is the Rust target triple. CI passes -Ptarget=… per matrix
// entry. When unset, we build for the host (whatever cargo's default
// target is — useful for local development).
val target: String? = (findProperty("target") as String?)?.takeIf { it.isNotBlank() }

// Where cargo will place the binary.
//   With --target T:  target/T/release/migration-tui
//   Without:          target/release/migration-tui
val cargoOutputDir: Directory = layout.projectDirectory.dir(
    if (target != null) "target/$target/release" else "target/release"
)

val binaryFile: RegularFile = cargoOutputDir.file("migration-tui")

// ─── inputs that should trigger a rebuild ─────────────────────────────
//
// Gradle's up-to-date check needs to know which files invalidate the
// cargo output. We list the tree explicitly rather than `**` because
// pulling in target/ would cause infinite invalidation.
val rustSourceTree: ConfigurableFileTree = fileTree(layout.projectDirectory) {
    include("Cargo.toml", "Cargo.lock")
    include("src/**/*.rs")
    include("tests/**/*.rs")
}

// ─── version plumbing ─────────────────────────────────────────────────
//
// Model C ("git tag is truth"): Cargo.toml's package.version is the
// perma-placeholder `0.0.0-dev`; the real release version comes from the
// Gradle property `tuiVersion`, which CI derives from the git tag. We
// inject it into cargo via `--config 'package.version=$tuiVersion'`,
// which overrides Cargo.toml without touching the source tree.
//
// Local dev:   no -PtuiVersion → binary is 0.0.0-dev. Fine.
// CI release:  -PtuiVersion=$VERSION_FROM_TAG → binary stamped with it.
val tuiVersion: String =
    (findProperty("tuiVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

// Make this visible to other subprojects (specifically :tui:release)
// through the project's `version`. They read project.version, not the
// `tuiVersion` extra property, so we propagate.
project.version = tuiVersion

// ─── version-source guard ─────────────────────────────────────────────
//
// Sanity check: Cargo.toml MUST stay at the placeholder. If someone
// edits it to a real version, fail loudly so we don't end up with two
// drifting sources of truth.
val versionCheck = tasks.register("versionCheck") {
    group = "verification"
    description = "Fail if Cargo.toml's package.version is not the placeholder '0.0.0-dev'"

    val cargoTomlFile = layout.projectDirectory.file("Cargo.toml")
    inputs.file(cargoTomlFile)

    doLast {
        val cargoToml = cargoTomlFile.asFile.readText()
        val versionRegex = Regex("""(?s)\[package\].*?\nversion\s*=\s*"([^"]+)"""")
        val cargoVersion = versionRegex.find(cargoToml)?.groupValues?.get(1)
            ?: throw GradleException("Could not find package.version in Cargo.toml")

        if (cargoVersion != "0.0.0-dev") {
            throw GradleException(
                "Cargo.toml package.version must stay at '0.0.0-dev' " +
                    "(see Model C in tui/build.gradle.kts header). " +
                    "Found: '$cargoVersion'. The release version is " +
                    "injected at build time via -PtuiVersion=… → " +
                    "cargo --config 'package.version=…'."
            )
        }
        logger.lifecycle("versionCheck: Cargo.toml placeholder intact ✓ (build version: $tuiVersion)")
    }
}

// ─── cargo invocations ────────────────────────────────────────────────
//
// One helper to set up rustup-aware Exec tasks. `cargo` from rustup's
// shim respects the toolchain in `rust-toolchain.toml` if present.
//
// `injectVersion` controls whether we add `--config
// 'package.version=$tuiVersion'`. For build/test we inject so the
// compiled binary reports the right version; for clippy we don't —
// clippy doesn't care about version and it just adds noise.
fun Exec.cargoSetup(action: String, vararg args: String, injectVersion: Boolean = true) {
    workingDir = layout.projectDirectory.asFile
    executable = "cargo"
    val argv = mutableListOf<String>()

    // --config flags must come BEFORE the subcommand.
    if (injectVersion) {
        argv += listOf("--config", "package.version=\"$tuiVersion\"")
    }

    argv += action
    if (target != null && action != "clippy") {
        argv += listOf("--target", target)
    }
    argv += args
    setArgs(argv)
}

val cargoTest = tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "cargo test --all-targets"
    dependsOn(versionCheck)
    inputs.files(rustSourceTree)
    cargoSetup("test", "--all-targets", "--quiet")
}

val cargoClippy = tasks.register<Exec>("cargoClippy") {
    group = "verification"
    description = "cargo clippy --all-targets -- -D warnings"
    dependsOn(versionCheck)
    inputs.files(rustSourceTree)
    cargoSetup("clippy", "--all-targets", "--", "-D", "warnings", injectVersion = false)
}

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "cargo build --release"
    dependsOn(versionCheck)
    inputs.files(rustSourceTree)
    outputs.file(binaryFile)
    cargoSetup("build", "--release")
}

// ─── lifecycle wiring ─────────────────────────────────────────────────

tasks.named("check") {
    dependsOn(cargoTest, cargoClippy)
}

tasks.named("assemble") {
    dependsOn(cargoBuild)
}

// ─── outgoing artifact: the compiled binary ───────────────────────────
//
// :tui:release pulls the binary from this Configuration. The contract
// is "give me the binary, I don't care which target you built it for"
// because the matrix dimension lives in CI, not in Gradle.
val tuiBinary by configurations.registering {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "release-binary"))
    }
}

artifacts {
    add(tuiBinary.name, binaryFile) {
        builtBy(cargoBuild)
        // Use the target triple in the artifact name so the release
        // subproject can disambiguate when multiple targets are built.
        name = "migration-tui"
        classifier = target ?: "host"
        extension = ""
    }
}

# Migration Companion image

This project builds a runnable AL2023-based image that also carries release
artifacts for downstream consumers.

Stable paths inside the image:

```text
/opt/migration-companion/artifacts/helm/migration-assistant.tgz
/opt/migration-companion/artifacts/cli/migration-assistant-cli.tar.gz
/opt/migration-companion/artifacts/cli/install.sh
/opt/migration-companion/artifacts/cli/installers/manifest.json
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-linux-amd64.tar.gz
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-linux-arm64.tar.gz
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-darwin-amd64.tar.gz
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-darwin-arm64.tar.gz
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-windows-amd64.tar.gz
/opt/migration-companion/artifacts/cli/installers/migration-assistant-cli-<version>-windows-arm64.tar.gz
/opt/migration-companion/cli/bin/migration-assistant
/opt/migration-companion/cli/bin/migration-assistant-bin
```

The image is published as a multi-arch manifest. Each image variant carries the
same files under `artifacts/`, while the runnable CLI binary is selected from
the Rust-built `linux-amd64` or `linux-arm64` output for that image platform.
Release builds feed in the macOS and Windows installer tarballs from their
native runner jobs before the companion image is built.

Local registry build:

```bash
./gradlew :deployment:k8s:migrationCompanion:buildMigrationCompanionImage \
  -PimageVersion=0.0.0-dev
```

Registry build:

```bash
./gradlew :deployment:k8s:migrationCompanion:buildMigrationCompanionImageToRegistry \
  -Pbuilder=local-remote-builder \
  -PregistryEndpoint=opensearchstaging \
  -PpublishStyle=separateRepos \
  -PimageVersion=0.0.0-dev
```

The final image base defaults to `docker.io/library/amazonlinux:2023`. Override
it with `-PmigrationCompanionBaseImage=...`.

Local companion builds default to Linux amd64 and Linux arm64 installers because
those also provide the image runtime binaries. Override or extend the local
installer target set with `-PcliInstallerTargets=classifier=rust-target,...`, or
copy prebuilt installer tarballs into the image with
`-PmigrationAssistantCliInstallersDir=/path/to/installers` and
`-PmigrationAssistantCliInstallersManifest=/path/to/manifest.json`.

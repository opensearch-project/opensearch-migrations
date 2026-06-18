# Migration Companion image

This project builds an AL2023-based image that carries the packaged Migration
Assistant Helm chart as a release artifact for downstream consumers.

Stable paths inside the image:

```text
/opt/migration-companion/artifacts/helm/migration-assistant.tgz
/opt/migration-companion/migration-assistant.tgz  # symlink to the chart above
```

The image is published as a multi-arch manifest. Each image variant carries the
same Helm chart under `artifacts/helm/`.

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

## Build contract

The `stageMigrationCompanionDockerContext` task (see `build.gradle`) stages the
Docker build context, and the `Dockerfile` may only `COPY` from what that task
produces. Today that is the `Dockerfile` itself plus
`artifacts/helm/migration-assistant.tgz`. If you change one side, change the
other — a `Dockerfile` that `COPY`s a directory the staging task does not
produce fails the build with `"/<dir>": not found`.

To validate the full release build path (including this image) without any
secrets, run the **Release dry-run** workflow
(`.github/workflows/release-dryrun.yml`) on your fork before tagging a release.

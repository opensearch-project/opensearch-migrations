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

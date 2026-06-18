ARG BASE_IMAGE=docker.io/library/amazonlinux:2023

FROM ${BASE_IMAGE}

ARG MIGRATION_ASSISTANT_VERSION=0.0.0-dev

LABEL org.opencontainers.image.title="OpenSearch Migrations Migration Companion" \
      org.opencontainers.image.description="Migration-assistant release artifact carrier with the packaged Helm chart" \
      org.opencontainers.image.version="${MIGRATION_ASSISTANT_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV MIGRATION_COMPANION_HOME=/opt/migration-companion \
    MIGRATION_ASSISTANT_HELM_CHART=/opt/migration-companion/artifacts/helm/migration-assistant.tgz

COPY artifacts/ /opt/migration-companion/artifacts/

RUN set -eux; \
    ln -sf /opt/migration-companion/artifacts/helm/migration-assistant.tgz /opt/migration-companion/migration-assistant.tgz

WORKDIR /workspace

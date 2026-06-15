ARG BASE_IMAGE=docker.io/library/amazonlinux:2023

FROM ${BASE_IMAGE}

ARG TARGETARCH
ARG MIGRATION_ASSISTANT_VERSION=0.0.0-dev

LABEL org.opencontainers.image.title="OpenSearch Migrations Migration Companion" \
      org.opencontainers.image.description="Runnable migration-assistant CLI image with packaged Helm chart and CLI release artifacts" \
      org.opencontainers.image.version="${MIGRATION_ASSISTANT_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV MIGRATION_COMPANION_HOME=/opt/migration-companion \
    MIGRATION_ASSISTANT_HELM_CHART=/opt/migration-companion/artifacts/helm/migration-assistant.tgz \
    MIGRATION_ASSISTANT_CLI_TARBALL=/opt/migration-companion/artifacts/cli/migration-assistant-cli.tar.gz \
    MIGRATE_CLI_BIN=/opt/migration-companion/cli/bin/migration-assistant-bin \
    MIGRATE_ENABLE_AGENT=1

COPY artifacts/ /opt/migration-companion/artifacts/
COPY cli/ /opt/migration-companion/cli/
COPY runtime-binaries/ /tmp/migration-companion-runtime-binaries/

RUN set -eux; \
    cp "/tmp/migration-companion-runtime-binaries/linux-${TARGETARCH}/migration-assistant" /opt/migration-companion/cli/bin/migration-assistant-bin; \
    rm -rf /tmp/migration-companion-runtime-binaries; \
    mkdir -p /usr/local/bin; \
    chmod +x /opt/migration-companion/cli/bin/migration-assistant; \
    chmod +x /opt/migration-companion/cli/bin/migration-assistant-bin; \
    ln -sf /opt/migration-companion/cli/bin/migration-assistant /usr/local/bin/migration-assistant; \
    ln -sf /opt/migration-companion/cli/bin/migration-assistant-bin /usr/local/bin/migration-assistant-bin; \
    ln -sf /opt/migration-companion/artifacts/helm/migration-assistant.tgz /opt/migration-companion/migration-assistant.tgz; \
    ln -sf /opt/migration-companion/artifacts/cli/migration-assistant-cli.tar.gz /opt/migration-companion/migration-assistant-cli.tar.gz

WORKDIR /workspace
ENTRYPOINT ["migration-assistant"]
CMD ["--help"]

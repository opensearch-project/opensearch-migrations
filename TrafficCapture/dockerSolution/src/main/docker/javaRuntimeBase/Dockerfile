# Base for the Java runtime images. Jib can't run commands, so the OS update happens here.
# The minimal image plus Corretto skips python3, dnf and other packages unused at runtime.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
FROM ${BASE_IMAGE}

ENV LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# Upgrade to the pinned AL2023 release (al2023Releasever in gradle.properties), then add Corretto.
# src.zip (JDK sources, ~50MB) isn't needed at runtime; the amazoncorretto image drops it too.
ARG AL2023_RELEASEVER=2023.12.20261120
RUN microdnf upgrade -y --releasever=${AL2023_RELEASEVER} --setopt=install_weak_deps=0 --nodocs && \
    microdnf install -y --releasever=${AL2023_RELEASEVER} --setopt=install_weak_deps=0 --nodocs \
        java-21-amazon-corretto-headless && \
    microdnf clean all && \
    rm -rf /var/cache/dnf "${JAVA_HOME}/lib/src.zip"

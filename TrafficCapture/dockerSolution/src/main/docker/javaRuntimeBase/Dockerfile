# Base for the Java runtime images. Jib can't run commands, so Corretto is installed here.
# The minimal image plus Corretto skips python3, dnf and other packages unused at runtime.
# Pinned by release tag and digest; microdnf installs from that image's own AL2023 release.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023.12.20261120.0-minimal@sha256:75a2d4226d9b98c914d7eed878a03bef4f365ad548be5d28d464ccba504cec79
FROM ${BASE_IMAGE}

ENV LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# src.zip (JDK sources, ~50MB) isn't needed at runtime; the amazoncorretto image drops it too.
RUN microdnf install -y --setopt=install_weak_deps=0 --nodocs \
        java-21-amazon-corretto-headless && \
    microdnf clean all && \
    rm -rf /var/cache/dnf "${JAVA_HOME}/lib/src.zip"

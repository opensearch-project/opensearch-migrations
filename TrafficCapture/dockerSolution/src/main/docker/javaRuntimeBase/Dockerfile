# Base for the Java runtime images. Jib can't run commands, so the OS update happens here.
ARG BASE_IMAGE=docker.io/library/amazoncorretto:21-al2023-headless
FROM ${BASE_IMAGE}

# Upgrade to the pinned AL2023 release (al2023Releasever in gradle.properties)
ARG AL2023_RELEASEVER=2023.12.20261120
RUN dnf upgrade -y --releasever=${AL2023_RELEASEVER} && dnf clean all && rm -rf /var/cache/dnf

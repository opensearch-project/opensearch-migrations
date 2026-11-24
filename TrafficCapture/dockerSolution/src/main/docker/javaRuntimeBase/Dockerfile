# Base for the Java runtime images. Jib can't run commands, so the OS update happens here.
# The minimal image plus Corretto skips python3, dnf and other packages unused at runtime.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
FROM ${BASE_IMAGE}

ENV LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# AL2023 pins microdnf to the image's release, so only --releasever=latest finds newer packages.
# OS_UPGRADE_DATE (set daily by the build) keeps the layer cache from replaying an old upgrade.
# src.zip (JDK sources, ~50MB) isn't needed at runtime; the amazoncorretto image drops it too.
ARG OS_UPGRADE_DATE=unset
RUN microdnf upgrade -y --releasever=latest --setopt=install_weak_deps=0 --nodocs && \
    microdnf install -y --setopt=install_weak_deps=0 --nodocs java-21-amazon-corretto-headless && \
    microdnf clean all && \
    rm -rf /var/cache/dnf "${JAVA_HOME}/lib/src.zip"

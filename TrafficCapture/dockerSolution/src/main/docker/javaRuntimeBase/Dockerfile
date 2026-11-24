# Base for the Java runtime images. Jib can't run commands, so the OS update happens here.
ARG BASE_IMAGE=docker.io/library/amazoncorretto:21-al2023-headless
FROM ${BASE_IMAGE}

# AL2023 pins dnf to the image's release, so only --releasever=latest finds newer packages.
# OS_UPGRADE_DATE (set daily by the build) keeps the layer cache from replaying an old upgrade.
ARG OS_UPGRADE_DATE=unset
RUN dnf upgrade -y --releasever=latest && dnf clean all && rm -rf /var/cache/dnf

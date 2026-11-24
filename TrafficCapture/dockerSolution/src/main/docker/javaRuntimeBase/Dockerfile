# Common base for the Java runtime images: the Jib-built traffic_replayer, capture_proxy and
# transformation_shim (Jib can't run commands, so OS updates have to happen here) and
# reindex_from_snapshot.
ARG BASE_IMAGE=docker.io/library/amazoncorretto:21-al2023-headless
FROM ${BASE_IMAGE}

# Install every OS update published since the base image was built. AL2023 locks dnf to the
# release the image was built from, so a plain 'dnf upgrade' finds nothing newer;
# --releasever=latest moves the image to the newest release. OS_UPGRADE_DATE is unused by the
# command; changing it only stops a layer cache from replaying an older upgrade.
ARG OS_UPGRADE_DATE=unset
RUN dnf upgrade -y --releasever=latest && \
    dnf clean all && \
    rm -rf /var/cache/dnf

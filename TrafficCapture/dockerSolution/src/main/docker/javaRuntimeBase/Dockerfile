# Common base for the Java runtime images: the Jib-built traffic_replayer, capture_proxy and
# transformation_shim (Jib can't run commands, so OS updates have to happen here) and
# reindex_from_snapshot.
#
# These images only need a JRE and a shell. The minimal AL2023 image plus the Corretto package
# leaves out python3, dnf, the rpm build/sign libraries and the X11/font libraries that the
# amazoncorretto image carries: about a third of its packages, none of them used at runtime.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
FROM ${BASE_IMAGE}

ENV LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# Install every OS update published since the base image was built, then Corretto. AL2023 locks
# the package manager to the release the image was built from, so a plain upgrade finds nothing
# newer; --releasever=latest moves the image to the newest release. OS_UPGRADE_DATE is unused by
# the command; changing it only stops a layer cache from replaying an older upgrade.
# The JDK source archive (lib/src.zip, ~50MB) isn't needed at runtime; the amazoncorretto image
# drops it too.
ARG OS_UPGRADE_DATE=unset
RUN microdnf upgrade -y --releasever=latest --setopt=install_weak_deps=0 --nodocs && \
    microdnf install -y --setopt=install_weak_deps=0 --nodocs java-21-amazon-corretto-headless && \
    microdnf clean all && \
    rm -rf /var/cache/dnf "${JAVA_HOME}/lib/src.zip"

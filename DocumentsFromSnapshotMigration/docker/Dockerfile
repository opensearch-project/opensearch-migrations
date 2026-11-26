# Using same base image as other Java containers in this repo
# Pinned by Corretto version tag and digest
ARG BASE_IMAGE=docker.io/library/amazoncorretto:21.0.13-al2023-headless@sha256:f8a793166c52b283f647980492ca88312d2de60f30849c1db98b4da3015d1c9a
FROM ${BASE_IMAGE}

# Install procps to use pgrep in entrypoint.sh
RUN dnf install -y procps && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Requires Gradle to genearte runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
COPY ./build/version/VERSION /rfs-app/VERSION
WORKDIR /rfs-app

# Copy the Java runner scripts into the container
COPY ./runJavaWithClasspath.sh /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh
COPY ./runJavaWithClasspathWithRepeat.sh /rfs-app/runJavaWithClasspathWithRepeat.sh
RUN chmod +x /rfs-app/runJavaWithClasspathWithRepeat.sh

# Copy the entry point script into the container
COPY ./entrypoint.sh /rfs-app/entrypoint.sh
RUN chmod +x /rfs-app/entrypoint.sh

CMD ["tail", "-f", "/dev/null"]

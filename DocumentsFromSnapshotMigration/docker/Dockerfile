# Using same base image as other Java containers in this repo
ARG BASE_IMAGE=migrations/java_runtime_base:latest
FROM ${BASE_IMAGE}

# Install procps-ng to use pgrep in entrypoint.sh (the minimal base only has microdnf)
RUN microdnf install -y --setopt=install_weak_deps=0 --nodocs procps-ng && \
    microdnf clean all && \
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

# Using same base image as other Java containers in this repo
FROM amazoncorretto:17-al2023-headless

# Install procps to use pgrep in entrypoint.sh
RUN dnf install -y procps && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Requires Gradle to genearte runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
COPY ./build/VERSION /rfs-app/VERSION
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

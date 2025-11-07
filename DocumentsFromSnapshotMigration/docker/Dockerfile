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
RUN printf "#!/bin/sh\nexec java -XX:MaxRAMPercentage=80.0 -cp /rfs-app/jars/*:. \"\$@\" " > /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh
RUN printf "#!/bin/sh\nwhile true; do\n  /rfs-app/runJavaWithClasspath.sh \"\$@\"\n  EXIT_CODE=\$?\n  if [ \$EXIT_CODE -ne 0 ]; then\n    exit \$EXIT_CODE\n  fi\n  echo \"Process exited with code 0, restarting...\"\ndone" > /rfs-app/runJavaWithClasspathWithRepeat.sh
RUN chmod +x /rfs-app/runJavaWithClasspathWithRepeat.sh

# Copy the entry point script into the container
COPY ./entrypoint.sh /rfs-app/entrypoint.sh
RUN chmod +x /rfs-app/entrypoint.sh

CMD ["tail", "-f", "/dev/null"]

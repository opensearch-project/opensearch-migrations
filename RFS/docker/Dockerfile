# Using same base image as other Java containers in this repo
FROM amazoncorretto:11-al2023-headless
# Requires Gradle to generate runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
WORKDIR /rfs-app
RUN printf "#!/bin/sh\njava -XX:MaxRAMPercentage=80.0 -cp /rfs-app/jars/*:. \"\$@\" " > /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh
CMD ["tail", "-f", "/dev/null"]
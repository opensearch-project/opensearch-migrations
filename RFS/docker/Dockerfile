# Build jars on container
#FROM amazoncorretto:11-alpine-jdk AS builder
#COPY /gradle /rfs-build/gradle
#COPY build.gradle /rfs-build
#COPY gradlew /rfs-build
#COPY settings.gradle /rfs-build
#COPY gradle.properties /rfs-build
#COPY /src /rfs-build/src
#COPY /buildSrc /rfs-build/buildSrc
#WORKDIR /rfs-build
#RUN ./gradlew copyRuntimeJars

# Using same base image as other Java containers in this repo
FROM openjdk:11-jre
#COPY --from=builder /rfs-build/build/runtimeJars /rfs-app/jars
# Requires Gradle to genearte runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
WORKDIR /rfs-app
RUN printf "#!/bin/sh\njava -XX:MaxRAMPercentage=80.0 -cp /rfs-app/jars/*:. \"\$@\" " > /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh
CMD ["tail", "-f", "/dev/null"]
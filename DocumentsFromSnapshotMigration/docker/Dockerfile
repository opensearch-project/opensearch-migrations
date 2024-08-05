# Using same base image as other Java containers in this repo
FROM amazoncorretto:11-al2023-headless

# Install the AWS CLI in the container
RUN yum update -y && yum install -y unzip
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install

# Requires Gradle to genearte runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
WORKDIR /rfs-app
RUN printf "#!/bin/sh\njava -XX:MaxRAMPercentage=80.0 -cp /rfs-app/jars/*:. \"\$@\" " > /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh

# Copy the entry point script into the container
COPY ./entrypoint.sh /rfs-app/entrypoint.sh
RUN chmod +x /rfs-app/entrypoint.sh

CMD ["tail", "-f", "/dev/null"]
# Use the official Jenkins LTS image as a parent image
FROM jenkins/jenkins:lts

# Switch to root to install additional packages
USER root

# Install necessary packages
RUN apt-get update && \
    apt-get install -y software-properties-common git docker docker.io npm vim && \
    add-apt-repository 'deb http://deb.debian.org/debian bullseye main contrib non-free' && \
    add-apt-repository 'deb http://deb.debian.org/debian bullseye-backports main contrib non-free' && \
    apt-get update && \
    apt-get install -y openjdk-11-jdk gradle awscli sudo && \
    npm install -g aws-cdk

# Set environment variables
ENV PATH /usr/lib/jvm/java-11-openjdk-amd64/bin:$PATH

# Clone necessary repositories
RUN git clone https://github.com/opensearch-project/opensearch-migrations

# Expose ports
EXPOSE 8080 50000

# Mount volumes
VOLUME /var/run/docker.sock /var/run/docker.sock
VOLUME jenkins_home:/var/jenkins_home
# Assumes aws creds directory exists
VOLUME jenkins_home/.aws:$HOME/.aws

# Change user back to Jenkins
USER jenkins
# CMD ["tail", "-f", "/dev/null"]


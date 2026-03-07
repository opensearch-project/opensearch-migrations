# Release Runner image for release pipeline
# Contains: skopeo, aws-cli, Maven, curl, jq
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    unzip \
    jq \
    skopeo \
    maven \
    && rm -rf /var/lib/apt/lists/*

# AWS CLI v2
RUN curl -sf "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip \
    && unzip -q /tmp/awscliv2.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/aws /tmp/awscliv2.zip

# Java 11 (for Maven — matches Jenkins release agent)
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /release

# CI Runner image for AWS integration tests
# Contains: Python, pipenv, pytest, aws-cli, kubectl, helm, jq, CDK, Gradle
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    unzip \
    git \
    jq \
    openssh-client \
    && rm -rf /var/lib/apt/lists/*

# AWS CLI v2
RUN curl -sf "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip \
    && unzip -q /tmp/awscliv2.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/aws /tmp/awscliv2.zip

# kubectl
RUN curl -sfL "https://dl.k8s.io/release/$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/$(dpkg --print-architecture)/kubectl" \
    -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl

# Helm
RUN curl -sf https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Node.js + CDK (for CDK deploy/destroy)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && npm install -g aws-cdk \
    && rm -rf /var/lib/apt/lists/*

# Java 17 (for Gradle builds)
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

# Python tools
RUN pip install --no-cache-dir pipenv pytest

WORKDIR /workspace

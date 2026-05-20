ARG BASE_IMAGE=migrations/elasticsearch_client_test_console:latest

# Stage 1: Download and install tools (kafka, kubectl, helm)
FROM ${BASE_IMAGE} AS migration-console-tools

RUN ARCH=$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/') && \
    if [ "$ARCH" != "amd64" ] && [ "$ARCH" != "arm64" ]; then \
        echo "Unsupported architecture: $ARCH" && exit 1; \
    fi && \
    echo "Using architecture: $ARCH" && \
    echo "$ARCH" > /arch.env

ARG HELM_VERSION="3.14.0"
ARG KUBECTL_VERSION="1.32.1"
ARG KAFKA_VERSION="3.9.2"
ARG STERN_VERSION="1.33.1"

# Get kafka distribution and unpack to 'kafka'
RUN mkdir -p /root/kafka-tools/kafka && \
    url_cdn="https://dlcdn.apache.org/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz" && \
    url_archive="https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz" && \
    curl --retry 2 --retry-delay 5 --retry-connrefused -fSL "$url_cdn" -o /tmp/kafka.tgz || \
        curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "$url_archive" -o /tmp/kafka.tgz && \
    tar -xzf /tmp/kafka.tgz -C /root/kafka-tools/kafka --strip-components=1 && \
    rm -f /tmp/kafka.tgz && \
    mkdir -p /root/kafka-tools/aws && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        https://github.com/aws/aws-msk-iam-auth/releases/download/v2.3.4/aws-msk-iam-auth-2.3.4-all.jar \
        -o /root/kafka-tools/kafka/libs/msk-iam-auth.jar

# Get kubectl and Helm distributions
RUN ARCH=$(cat /arch.env) && \
    mkdir -p /k8s/bin && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "https://dl.k8s.io/release/v${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" -o /k8s/bin/kubectl && \
    chmod +x /k8s/bin/kubectl

RUN ARCH=$(cat /arch.env) && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-${ARCH}.tar.gz" | tar -xz && \
    mv linux-${ARCH}/helm /k8s/bin/

# Install stern - used by 'workflow log -f' for multi-pod log tailing
RUN ARCH=$(cat /arch.env) && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        "https://github.com/stern/stern/releases/download/v${STERN_VERSION}/stern_${STERN_VERSION}_linux_${ARCH}.tar.gz" | tar -xz && \
    mv stern /k8s/bin/ && \
    chmod +x /k8s/bin/stern


# Stage 2: Main migration console base image with Python environment and scripts
FROM ${BASE_IMAGE}

# Define the virtual environment path
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1
ENV PATH="/.venv/bin:${PATH}"

# Copy tools from previous stage
COPY --from=migration-console-tools /root/kafka-tools /root/kafka-tools
COPY --from=migration-console-tools /k8s/bin/ /usr/local/bin

RUN kubectl completion bash > /etc/bash_completion.d/kubectl && \
    helm completion bash > /etc/bash_completion.d/helm && \
    mkdir -p /config

WORKDIR /root

# Install Node.js for config processor and workflow template creation
# NPM is to setup the schema checking that coc uses
RUN dnf install -y  \
      --setopt=install_weak_deps=False \
      --setopt=tsflags=nodocs \
      --setopt=keepcache=False \
      --setopt=plugins=0 \
      --setopt=logfilelevel=0 \
        nodejs22 \
        nodejs22-npm \
        bash-completion \
        && \
        dnf clean all

# Install mount-s3 for direct S3 bucket mounting (requires SYS_ADMIN capability at runtime)
RUN MOUNT_S3_VERSION="1.22.2" && \
    ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then ARCH="arm64"; fi && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        "https://s3.amazonaws.com/mountpoint-s3-release/${MOUNT_S3_VERSION}/${ARCH}/mount-s3-${MOUNT_S3_VERSION}-${ARCH}.rpm" \
        -o /tmp/mount-s3.rpm && \
    dnf install -y /tmp/mount-s3.rpm && \
    rm -f /tmp/mount-s3.rpm

COPY setupVimSchemaChecker.sh /tmp/setupVimSchemaChecker.sh
RUN chmod +x /tmp/setupVimSchemaChecker.sh && \
    /tmp/setupVimSchemaChecker.sh && \
    rm /tmp/setupVimSchemaChecker.sh

# Copy migration console scripts and configs
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
COPY kafkaExport.sh /root/kafka-tools
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

COPY loadServicesFromParameterStore.sh /root/
RUN chmod ug+x /root/loadServicesFromParameterStore.sh

COPY start-console.sh /root/
RUN chmod +x /root/start-console.sh

# Copy Python libraries and install dependencies
# Note: .dockerignore should exclude .venv, but we ensure clean install
WORKDIR /root
COPY lib /root/lib

WORKDIR /root/lib/console_link
RUN pipenv install --deploy
WORKDIR /root/lib/integ_test
RUN pipenv install --deploy

COPY cluster_tools /root/cluster_tools
RUN rm -rf /root/cluster_tools/.venv
WORKDIR /root/cluster_tools
RUN pipenv install
WORKDIR /root

COPY nodeStaging/configProcessor /root/configProcessor

RUN chmod +x /root/configProcessor/*.sh
ENV CONFIG_PROCESSOR_DIR=/root/configProcessor
ENV NODEJS=node-22

COPY nodeStaging/workflowTemplates /root/workflows
RUN chmod +x /root/workflows/.workflowScripts/*.sh
COPY workflowConfigToServicesConfig.jq /root/
ENV WORKFLOW_CONFIG_JQ_SCRIPT=/root/workflowConfigToServicesConfig.jq

# Console setup bash completion and venv for interactive access
RUN echo '. /.venv/bin/activate' >> /etc/profile.d/venv.sh && \
    echo '. /etc/profile.d/bash_completion.sh' >> ~/.bashrc && \
    echo '. /etc/profile.d/venv.sh' >> ~/.bashrc && \
    echo 'echo Welcome to the Migration Assistant Console' >> ~/.bashrc && \
    echo 'eval "$(register-python-argcomplete cluster_tools)"' >> ~/.bashrc && \
    echo 'console completion bash 2> /dev/null > /usr/share/bash-completion/completions/console' >> ~/.bashrc && \
    echo 'workflow util completions bash > /usr/share/bash-completion/completions/workflow' >> ~/.bashrc && \
    echo '. /usr/share/bash-completion/completions/workflow' >> ~/.bashrc && \
    echo 'PS1="(\\t) \\[\\e[92m\\]migration-console \\[\\e[0m\\](\\w) -> "'>> ~/.bashrc

# Set ENV to control startup script in /bin/sh mode
ENV ENV=/root/.bashrc

CMD ["/root/start-console.sh"]

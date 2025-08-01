ARG BASE_IMAGE=migrations/elasticsearch_client_test_console:latest

FROM ${BASE_IMAGE} AS migration-console-base

RUN ARCH=$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/') && \
    if [ "$ARCH" != "amd64" ] && [ "$ARCH" != "arm64" ]; then \
        echo "Unsupported architecture: $ARCH" && exit 1; \
    fi && \
    echo "Using architecture: $ARCH" && \
    echo $ARCH > /arch.env

ARG HELM_VERSION="3.14.0"
ARG KUBECTL_VERSION="1.32.1"

# Get kafka distribution and unpack to 'kafka'
RUN mkdir -p /root/kafka-tools/kafka && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz -o /tmp/kafka.tgz && \
    tar -xzf /tmp/kafka.tgz -C /root/kafka-tools/kafka --strip-components=1 && \
    rm -f /tmp/kafka.tgz
RUN mkdir -p /root/kafka-tools/aws && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL https://github.com/aws/aws-msk-iam-auth/releases/download/v2.0.3/aws-msk-iam-auth-2.0.3-all.jar -o /root/kafka-tools/kafka/libs/msk-iam-auth.jar

# Get kubectl and Helm distributions
RUN ARCH=$(cat /arch.env) && \
    mkdir -p /k8s/bin && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "https://dl.k8s.io/release/v${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" -o /k8s/bin/kubectl && \
    chmod +x /k8s/bin/kubectl

RUN ARCH=$(cat /arch.env) && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-${ARCH}.tar.gz" | tar -xz && \
    mv linux-${ARCH}/helm /k8s/bin/

# Install etcdctl
RUN ARCH=$(cat /arch.env) && \
    ETCD_VER=v3.5.21 && \
    DOWNLOAD_URL=https://github.com/etcd-io/etcd/releases/download && \
    mkdir -p /etcd/bin && \
    curl -L ${DOWNLOAD_URL}/${ETCD_VER}/etcd-${ETCD_VER}-linux-${ARCH}.tar.gz | tar -xz && \
    mv etcd-${ETCD_VER}-linux-${ARCH}/etcdctl /etcd/bin && \
    rm -rf etcd-${ETCD_VER}-linux-amd64 etcd.tar.gz


FROM ${BASE_IMAGE}

# Define the virtual environment path
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

COPY --from=migration-console-base /root/kafka-tools /root/kafka-tools
COPY --from=migration-console-base /k8s/bin/ /usr/local/bin
COPY --from=migration-console-base /etcd/bin/ /usr/local/bin

RUN kubectl completion bash > /etc/bash_completion.d/kubectl && \
    helm completion bash > /etc/bash_completion.d/helm

RUN mkdir -p /config

WORKDIR /root
# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY staging/kafkaCommandLineFormatter/lib /root/kafka-tools/
ENV CLASSPATH=/root/kafka-tools/kafkaCommandLineFormatter.jar

COPY staging/CreateSnapshot /root/createSnapshot
COPY staging/MetadataMigration /root/metadataMigration

COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools

COPY kafkaExport.sh /root/kafka-tools
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

COPY loadServicesFromParameterStore.sh /root/
RUN chmod ug+x /root/loadServicesFromParameterStore.sh

COPY start-console.sh /root/
RUN chmod +x /root/start-console.sh

COPY VERSION /root/VERSION

WORKDIR /root
COPY lib /root/lib
WORKDIR /root/lib/console_link
RUN pipenv install --deploy
WORKDIR /root/lib/integ_test
RUN pipenv install --deploy

COPY cluster_tools /root/cluster_tools
WORKDIR /root/cluster_tools
RUN pipenv install
WORKDIR /root

# Console setup bash completion and venv for interactive access
RUN echo '. /.venv/bin/activate' >> /etc/profile.d/venv.sh
RUN dnf install -y bash-completion --setopt=install_weak_deps=False && \
        dnf clean all && \
        rm -rf /var/cache/dnf

RUN echo '. /etc/profile.d/bash_completion.sh' >> ~/.bashrc && \
    echo '. /etc/profile.d/venv.sh' >> ~/.bashrc && \
    echo 'echo Welcome to the Migration Assistant Console' >> ~/.bashrc && \
    echo 'eval "$(register-python-argcomplete cluster_tools)"' >> ~/.bashrc && \
    echo 'source /.venv/bin/activate' >> ~/.bashrc && \
    echo 'console --config-file=/root/lib/console_link/services.yaml completion bash > /usr/share/bash-completion/completions/console' >> ~/.bashrc && \
    echo 'PS1="(\t) \[\e[92m\]migration-console \[\e[0m\](\w) -> "' >> ~/.bashrc

COPY workflows /root/workflows

# Set ENV to control startup script in /bin/sh mode
ENV ENV=/root/.bashrc

CMD ["/root/start-console.sh"]

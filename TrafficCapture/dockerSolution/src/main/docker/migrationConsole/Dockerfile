FROM migrations/elasticsearch_client_test_console:latest

# Define the virtual environment path
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws

WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget --tries=5 --retry-connrefused --waitretry=15 -O-  https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget --tries=5 --retry-connrefused --waitretry=15 -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v2.0.3/aws-msk-iam-auth-2.0.3-all.jar
WORKDIR /root

# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY staging/kafkaCommandLineFormatter/lib /root/kafka-tools/
ENV CLASSPATH=/root/kafka-tools/kafkaCommandLineFormatter.jar

COPY staging/CreateSnapshot /root/createSnapshot
COPY staging/MetadataMigration /root/metadataMigration

COPY osiPipelineTemplate.yaml /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools

COPY kafkaExport.sh /root/kafka-tools
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

COPY loadServicesFromParameterStore.sh /root/
RUN chmod ug+x /root/loadServicesFromParameterStore.sh

WORKDIR /root
COPY lib /root/lib
WORKDIR /root/lib/console_link
RUN pipenv install --deploy
WORKDIR /root/lib/integ_test
RUN pipenv install --deploy

COPY console_api /root/console_api
WORKDIR /root/console_api
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
    echo 'eval "$(register-python-argcomplete cluster_tools)"' >> ~/.bashrc

# Set ENV to control startup script in /bin/sh mode
ENV ENV=/root/.bashrc

CMD /root/loadServicesFromParameterStore.sh && tail -f /dev/null

# Experimental console API, uncomment to use in addition to uncomment port mapping in docker-compose.yml
#CMD /root/loadServicesFromParameterStore.sh && pipenv run python /root/console_api/manage.py runserver_plus 0.0.0.0:8000 --cert-file cert.crt

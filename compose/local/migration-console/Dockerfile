FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update
RUN apt-get install -y --no-install-recommends \
        python3.9 \
        python3-pip \
        python3-dev \
        openjdk-11-jre-headless \
        wget \
        gcc \
        libc-dev \
        git \
        curl \
        vim \
        jq \
        unzip \
        less
RUN pip3 install \
        urllib3==1.25.11 \
        opensearch-benchmark==1.1.0 \
        awscurl==0.32 \
        tqdm==4.66.1

# Experimental - Migration API
#RUN apt-get install nginx 
#RUN update-rc.d nginx defaults
# TODO Move these to requirements.txt file
RUN pip3 install \
        django==5.0.1 \
        djangorestframework==3.14.0 \
        django-extensions==3.2.3 \
        werkzeug==3.0.1

# End Experimental - Migration API

# TODO upon the next release of opensearch-benchmark the awscli package should be installed by pip3, with the expected boto3 version upgrade resolving the current conflicts between opensearch-benchmark and awscli
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install && rm -rf aws awscliv2.zip
RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/runTestBenchmarks.sh /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/humanReadableLogs.py /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/catIndices.sh /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/showFetchMigrationCommand.sh /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/setupIntegTests.sh /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/msk-iam-auth.properties /root/kafka-tools/aws
COPY ./TrafficCapture/dockerSolution/src/main/docker/migrationConsole/kafkaCmdRef.md /root/kafka-tools
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/catIndices.sh
RUN chmod ug+x /root/showFetchMigrationCommand.sh
WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v1.1.9/aws-msk-iam-auth-1.1.9-all.jar
WORKDIR /root

# Experimental
#CMD python3 manage.py runserver_plus --reload-type=stat 0.0.0.0:8000
CMD python3 /console/console_api/manage.py runserver_plus 0.0.0.0:8000
CMD tail -f /dev/null
FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

COPY requirements.txt /requirements-test-console.txt

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev openjdk-11-jre-headless wget gcc libc-dev git curl vim jq unzip less && \
    pip3 install -r /requirements-test-console.txt

COPY runTestBenchmarks.sh /root/
COPY simpleDocumentGenerator.py /root/
COPY catIndices.sh /root/

RUN ln -s /usr/bin/python3 /usr/bin/python

RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/simpleDocumentGenerator.py
RUN chmod ug+x /root/catIndices.sh

CMD tail -f /dev/null

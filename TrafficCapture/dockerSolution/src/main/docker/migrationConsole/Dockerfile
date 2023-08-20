FROM ubuntu:focal

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev gcc libc-dev git curl && \
    pip3 install opensearch-benchmark

COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
WORKDIR /root

CMD tail -f /dev/null

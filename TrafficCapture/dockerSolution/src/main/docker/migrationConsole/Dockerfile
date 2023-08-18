FROM ubuntu:focal

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.11.4 python3-pip python3-dev gcc libc-dev git curl && \
    pip3 install urllib3==1.25.11 opensearch-benchmark==1.1.0

COPY runTestBenchmarks.sh /root/
RUN chmod ugo+x /root/runTestBenchmarks.sh
WORKDIR /root

CMD tail -f /dev/null
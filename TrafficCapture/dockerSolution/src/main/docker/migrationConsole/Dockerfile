FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev gcc libc-dev git curl vim && \
    pip3 install urllib3==1.25.11 opensearch-benchmark==1.1.0 awscurl tqdm

COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
COPY catIndices.sh /root/
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/catIndices.sh
WORKDIR /root

CMD tail -f /dev/null
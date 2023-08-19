FROM ubuntu:focal

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev gcc libc-dev git curl && \
    pip3 install urllib3==1.25.11 opensearch-benchmark==1.1.0 tqdm

COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
RUN chmod ugo+x /root/runTestBenchmarks.sh
RUN chmod ugo+x /root/humanReadableLogs.py
WORKDIR /root

CMD tail -f /dev/null
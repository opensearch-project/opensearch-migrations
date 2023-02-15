FROM ubuntu:focal

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev gcc libc-dev git && \
    pip3 install opensearch-benchmark

# In my testing, running all four of these took ~1.5 minutes on my Mac
CMD echo "Running opensearch-benchmark w/ 'geonames' workload..." && \
    opensearch-benchmark execute_test --distribution-version=1.0.0 --target-host=host.docker.internal:80 --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" && \
    echo "Running opensearch-benchmark w/ 'http_logs' workload..." && \
    opensearch-benchmark execute_test --distribution-version=1.0.0 --target-host=host.docker.internal:80 --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" && \
    echo "Running opensearch-benchmark w/ 'nested' workload..." && \
    opensearch-benchmark execute_test --distribution-version=1.0.0 --target-host=host.docker.internal:80 --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" && \
    echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." && \
    opensearch-benchmark execute_test --distribution-version=1.0.0 --target-host=host.docker.internal:80 --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"
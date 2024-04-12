#!/bin/bash

generate_data_requests() {
  endpoint="http://localhost:9200"
  # If auth or SSL is used, the correlating OSB options should be provided in this array
  options=()
  client_options=$(IFS=,; echo "${options[*]}")
  set -o xtrace

  echo "Running opensearch-benchmark workloads against ${endpoint}"
  echo "Running opensearch-benchmark w/ 'geonames' workload..." &&
  opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
  echo "Running opensearch-benchmark w/ 'http_logs' workload..." &&
  opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
  echo "Running opensearch-benchmark w/ 'nested' workload..." &&
  opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options &&
  echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." &&
  opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" --client-options=$client_options
}

should_generate_data=$1

if [[ "$should_generate_data" == true ]]; then
   /usr/local/bin/docker-entrypoint.sh eswrapper & echo $! > /tmp/esWrapperProcess.pid && sleep 10 && generate_data_requests
else
   mkdir -p /usr/share/elasticsearch/data/nodes
fi
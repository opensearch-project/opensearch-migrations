## Test Resources

This directory tree contains different archive files for loading small data directories, preloaded with indexed data, onto the node during the Docker build stage for this image. This allows for quick testing of RFS in Docker currently, but should be revisited for larger datasets and multi node docker clusters. A breakdown is as follows.

#### small-benchmark-single-node.tar.gz

This dataset comprises four small OpenSearch Benchmark workloads resulting in the following starting indices:
```shell
health status index          uuid                   pri rep docs.count docs.deleted store.size pri.store.size
green  open   logs-221998    9zHMJq5ARryrk-wLGpTtpw   5   0       1000            0    167.9kb        167.9kb
green  open   logs-211998    T5tzxvMFTv-TsZmMPmEZVA   5   0       1000            0    163.5kb        163.5kb
green  open   geonames       IDm1LtwhTPOBhC6WkKG35g   5   0       1000            0    406.6kb        406.6kb
green  open   reindexed-logs 5FpdarRdSsWu9mzMkP4EPw   5   0          0            0        1kb            1kb
green  open   nyc_taxis      H5QyQJo1RDa2Ga6oWW74HA   1   0       1000            0    171.5kb        171.5kb
green  open   logs-231998    Sx6nGowOQtS5rRwVU87B8A   5   0       1000            0    168.5kb        168.5kb
green  open   sonested       Y4KMmkrfTO-elQpEMes-QA   1   0       2977            0    463.8kb        463.8kb
green  open   logs-241998    ag9Cr1c0TtePeH6HUKh0Hw   5   0       1000            0    170.2kb        170.2kb
green  open   logs-181998    VT9bbJg_Qtaiak_duevKBQ   5   0       1000            0    168.7kb        168.7kb
green  open   logs-201998    pK_pZTRsQzafx-XWv7EuZw   5   0       1000            0    165.3kb        165.3kb
green  open   logs-191998    rIAoYLV-QZqkuvzMGNxnwQ   5   0       1000            0    163.7kb        163.7kb

```

This can be reproduced by running the following script on the container for this image, and exporting the `data` directory
```shell
#!/bin/bash 

endpoint="http://localhost:9200"
set -o xtrace

echo "Running opensearch-benchmark workloads against ${endpoint}"
echo "Running opensearch-benchmark w/ 'geonames' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" &&
echo "Running opensearch-benchmark w/ 'http_logs' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" &&
echo "Running opensearch-benchmark w/ 'nested' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nested --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1" &&
echo "Running opensearch-benchmark w/ 'nyc_taxis' workload..." &&
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$endpoint --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"


```

#### no-data.tar.gz
This is a placeholder file, which is used as a default, and preloads no data onto the cluster and simply contains an empty `data` directory
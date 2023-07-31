# Traffic Capture

## Table of Contents

- [Traffic Capture](#traffic-capture)
    - [Table of Contents](#table-of-contents)
    - [Overview](#overview)
    - [Tools](#tools)
        - [Docker Solution](#docker-solution)
        - [Traffic Capture Proxy Server](#traffic-capture-proxy-server)
        - [Traffic Replayer](#traffic-replayer)
        - [Capture Kafka Offloader](#capture-kafka-offloader)

## Overview

This directory provides a suite of tools designed to facilitate the migration and upgrade of OpenSearch clusters. 
Each tool serves a unique function, and are used in combination to provide an end-to-end migrations and upgrades solution.

## Tools

### Docker Solution

The Docker Solution is a containerized environment that allows for the easy setup and deployment of the other tools in this repository.
For more details, check out the [Docker Solution README](dockerSolution/README.md).

### Traffic Capture Proxy Server

The Traffic Capture Proxy Server acts as a middleman, capturing traffic going to a source, which can then be used by the Traffic Replayer.

### Traffic Replayer

The Traffic Replayer consumes streams of IP packets that were previously recorded by the Traffic Capture Proxy Server and replays the requests to another HTTP
server, recording the packet traffic of the new interactions.

Learn more about its functionality and setup here: [Traffic Replayer](trafficReplayer/README.md)

### OpenSearch Benchmark

A container with a [script](dockerSolution/src/main/docker/openSearchBenchmark/runTestBenchmarks.sh) to run different [OpenSearch Benchmark](https://github.com/opensearch-project/opensearch-benchmark) workloads
is brought up as part of the solution.

The workloads are started with the Traffic Capture Proxy Server set as the target, which will capture the requests sent by OpenSearch Benchmark,
save them in Kafka before they continue to a "Source Cluster".
The Traffic Capture Puller then takes the captured traffic and sends it to the Traffic Replayer.
The Traffic Replayer's logs (Tuples consisting of a request, a pair of responses) is then stored in persistent storage for the user's own analytics usages

Note that the script must be manually started.

Partial example output of OpenSearch Benchmark:

```

Running opensearch-benchmark w/ 'geonames' workload...

   ____                  _____                      __       ____                  __                         __
/ __ \____  ___  ____ / ___/___  ____ ___________/ /_     / __ )___  ____  _____/ /_  ____ ___  ____ ______/ /__
/ / / / __ \/ _ \/ __ \\__ \/ _ \/ __ `/ ___/ ___/ __ \   / __  / _ \/ __ \/ ___/ __ \/ __ `__ \/ __ `/ ___/ //_/
/ /_/ / /_/ /  __/ / / /__/ /  __/ /_/ / /  / /__/ / / /  / /_/ /  __/ / / / /__/ / / / / / / / / /_/ / /  / ,<
\____/ .___/\___/_/ /_/____/\___/\__,_/_/   \___/_/ /_/  /_____/\___/_/ /_/\___/_/ /_/_/ /_/ /_/\__,_/_/  /_/|_|
/_/

[INFO] You did not provide an explicit timeout in the client options. Assuming default of 10 seconds.
[INFO] Downloading workload data (20.5 kB total size)                             [100.0%]
[INFO] Decompressing workload data from [/root/.benchmark/benchmarks/data/geonames/documents-2-1k.json.bz2] to [/root/.benchmark/benchmarks/data/geonames/documents-2-1k.json] ... [OK]
[INFO] Preparing file offset table for [/root/.benchmark/benchmarks/data/geonames/documents-2-1k.json] ... [OK]
[INFO] Executing test with workload [geonames], test_procedure [append-no-conflicts] and provision_config_instance ['external'] with version [7.10.2].

[WARNING] indexing_total_time is 14 ms indicating that the cluster is not in a defined clean state. Recorded index time metrics may be misleading.
[WARNING] refresh_total_time is 65 ms indicating that the cluster is not in a defined clean state. Recorded index time metrics may be misleading.
Running delete-index                                                           [100% done]
Running create-index                                                           [100% done]
Running check-cluster-health                                                   [100% done]
Running index-append                                                           [100% done]
```

### Capture Kafka Offloader

The Capture Kafka Offloader will act as a Kafka Producer for offloading captured traffic logs to the configured Kafka cluster.

Learn more about its functionality and setup here: [Capture Kafka Offloader](captureKafkaOffloader/README.md)

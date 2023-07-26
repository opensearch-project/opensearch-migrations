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

### Capture Kafka Offloader

The Capture Kafka Offloader will act as a Kafka Producer for offloading captured traffic logs to the configured Kafka cluster.

Learn more about its functionality and setup here: [Capture Kafka Offloader](captureKafkaOffloader/README.md)

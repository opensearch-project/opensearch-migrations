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
    - [Building](#building)
        - [Gradle Scans](#gradle-scans)
    - [Publishing](#publishing)

## Overview

This directory provides a suite of tools designed to facilitate the migration and upgrade of OpenSearch clusters. 
Each tool serves a unique function, and are used in combination to provide an end-to-end migrations and upgrades solution.

## Tools

### Docker Solution

The Docker Solution is a containerized environment that allows for the easy setup and deployment of the other tools in this repository.
For more details, check out the [Docker Solution README](dockerSolution/README.md).

### Traffic Capture Proxy Server

The Traffic Capture Proxy Server acts as a middleman, capturing traffic going to a source, which can then be used by the Traffic Replayer.

This tool can be attached to coordinator nodes in clusters with a minimum of two coordinator nodes and start capturing traffic
while having zero downtime on the cluster. Be aware that zero downtime is only achievable **if** the remaining nodes in-service can handle the additional load on the cluster. 
More details on attaching a Capture Proxy can be found here: [Capture Proxy](trafficCaptureProxyServer/README.md).

### Traffic Replayer

The Traffic Replayer consumes streams of IP packets that were previously recorded by the Traffic Capture Proxy Server and replays the requests to another HTTP
server, recording the packet traffic of the new interactions.

Learn more about its functionality and setup here: [Traffic Replayer](trafficReplayer/README.md)

### Migration Console

A container with a [script](dockerSolution/src/main/docker/elasticsearchTestConsole/runTestBenchmarks.sh) to run different [OpenSearch Benchmark](https://github.com/opensearch-project/opensearch-benchmark) workloads
is brought up as part of the solution.

The workloads are started with the Traffic Capture Proxy Server set as the target, which will capture the requests sent by OpenSearch Benchmark,
save them in Kafka before they continue to a "Source Cluster".
The Traffic Capture Puller then takes the captured traffic and sends it to the Traffic Replayer.
The Traffic Replayer's logs (Tuples consisting of a request, a pair of responses) is then stored in persistent storage for the user's own analytics usages

Note that the script must be manually started.

Partial example output of the OpenSearch Benchmark tool:

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

The `runTestBenchmarks` tool has a few configurable options. It will attempt to guess the correct endpoint to send traffic to,
and it will automatically attach the basic auth user/password `admin`/`admin`.

To set a custom endpoint, specify it with `--endpoint`, for example `./runTestBenchmarks --endpoint https://capture-proxy-domain.com:9200`.

To set custom basic auth params, use `--auth_user` and `--auth_pass`. To prevent the script from attaching _any_ auth params, use the `--no_auth` flag.
This flag overrides any other auth params, so if you use both `--auth_user` and `--no_auth`, the end result will be no auth being applied.

As an example of including multiple options:
```sh
./runTestBenchmarks --endpoint https://capture-proxy-domain.com:9200 --auth_pass Admin123!
```

will send requests to `capture-proxy-domain.com`, using the auth combo `admin`/`Admin123!`.

Support for Sigv4 signing and other auth options may be a future option.

#### Understanding Data from the Replayer

The Migration Console can be used to access and help interpret the data from the replayer.

The data generated from the replayer is stored on an Elastic File System volume shared between the Replayer and Migration Console.
It is mounted to the Migration Console at the path `/shared_replayer_output`. The Replayer generates files named `output_tuples.log`.
These files are rolled over as they hit 10 MB to a series of `output_tuples-%d{yyyy-MM-dd-HH:mm}.log` files.

The data in these files is in the format of JSON lines, each of which is a log message containing a specific source-target-request-response(s) tuple.

To read the tuples from the Migration Console follow the command shown below.
Note: This may expose sensitive security details as the tuples may contain raw document and authorization details.

<details>
<summary>See example tuples:</summary>

```sh
$ cat /shared-logs-output/traffic-replayer-default/*/tuples/tuples.log | jq
{
  "sourceRequest": {
    "Host": [
      "localhost:9200"
    ],
    "Authorization": [
      "Basic YWRtaW46YWRtaW4="
    ],
    "User-Agent": [
      "curl/8.7.1"
    ],
    "Accept": [
      "*/*"
    ],
    "Request-URI": "/",
    "Method": "GET",
    "HTTP-Version": "HTTP/1.1",
    "payload": {
      "inlinedBase64Body": ""
    }
  },
  "sourceResponse": {
    "content-type": [
      "application/json; charset=UTF-8"
    ],
    "content-length": [
      "538"
    ],
    "HTTP-Version": "HTTP/1.1",
    "Status-Code": 200,
    "Reason-Phrase": "OK",
    "response_time_ms": 10,
    "payload": {
      "inlinedJsonBody": {
        "name": "2383a194365a",
        "cluster_name": "docker-cluster",
        "cluster_uuid": "fhZZvFEiS92srLRLvGXKrA",
        "version": {
          "number": "7.10.2",
          "build_flavor": "oss",
          "build_type": "docker",
          "build_hash": "747e1cc71def077253878a59143c1f785afa92b9",
          "build_date": "2021-01-13T00:42:12.435326Z",
          "build_snapshot": false,
          "lucene_version": "8.7.0",
          "minimum_wire_compatibility_version": "6.8.0",
          "minimum_index_compatibility_version": "6.0.0-beta1"
        },
        "tagline": "You Know, for Search"
      }
    }
  },
  "targetRequest": {
    "Host": [
      "opensearchtarget"
    ],
    "Authorization": [
      "Basic YWRtaW46bXlTdHJvbmdQYXNzd29yZDEyMyE="
    ],
    "User-Agent": [
      "curl/8.7.1"
    ],
    "Accept": [
      "*/*"
    ],
    "Request-URI": "/",
    "Method": "GET",
    "HTTP-Version": "HTTP/1.1",
    "payload": {
      "inlinedBase64Body": ""
    }
  },
  "targetResponses": [
    {
      "content-type": [
        "application/json; charset=UTF-8"
      ],
      "content-length": [
        "568"
      ],
      "HTTP-Version": "HTTP/1.1",
      "Status-Code": 200,
      "Reason-Phrase": "OK",
      "response_time_ms": 112,
      "payload": {
        "inlinedJsonBody": {
          "name": "758b4454da60",
          "cluster_name": "docker-cluster",
          "cluster_uuid": "Uu3orSZ-Tie1Jnq7p-GrVw",
          "version": {
            "distribution": "opensearch",
            "number": "2.15.0",
            "build_type": "tar",
            "build_hash": "61dbcd0795c9bfe9b81e5762175414bc38bbcadf",
            "build_date": "2024-06-20T03:27:32.562036890Z",
            "build_snapshot": false,
            "lucene_version": "9.10.0",
            "minimum_wire_compatibility_version": "7.10.0",
            "minimum_index_compatibility_version": "7.0.0"
          },
          "tagline": "The OpenSearch Project: https://opensearch.org/"
        }
      }
    }
  ],
  "connectionId": "0242acfffe12000b-0000000a-0000000f-d1aa22e30e1211a4-eba39e55.0",
  "numRequests": 1,
  "numErrors": 0
}
{
  "sourceRequest": {
    "Host": [
      "localhost:9200"
    ],
    "Authorization": [
      "Basic YWRtaW46YWRtaW4="
    ],
    "User-Agent": [
      "curl/8.7.1"
    ],
    "Accept": [
      "*/*"
    ],
    "Request-URI": "/_cat/indices",
    "Method": "GET",
    "HTTP-Version": "HTTP/1.1",
    "payload": {
      "inlinedBase64Body": ""
    }
  },
  "sourceResponse": {
    "content-type": [
      "text/plain; charset=UTF-8"
    ],
    "content-length": [
      "162"
    ],
    "HTTP-Version": "HTTP/1.1",
    "Status-Code": 200,
    "Reason-Phrase": "OK",
    "response_time_ms": 12,
    "payload": {
      "inlinedTextBody": "green  open searchguard             KRWtFn0nQwi6BdObOAsCYQ 1 0 8 0 45.4kb 45.4kb\nyellow open sg7-auditlog-2024.10.04 F2PV5IeTT0aVxP_BmuJSaQ 1 1 4 0 57.8kb 57.8kb\n"
    }
  },
  "targetRequest": {
    "Host": [
      "opensearchtarget"
    ],
    "Authorization": [
      "Basic YWRtaW46bXlTdHJvbmdQYXNzd29yZDEyMyE="
    ],
    "User-Agent": [
      "curl/8.7.1"
    ],
    "Accept": [
      "*/*"
    ],
    "Request-URI": "/_cat/indices",
    "Method": "GET",
    "HTTP-Version": "HTTP/1.1",
    "payload": {
      "inlinedBase64Body": ""
    }
  },
  "targetResponses": [
    {
      "content-type": [
        "text/plain; charset=UTF-8"
      ],
      "content-length": [
        "249"
      ],
      "HTTP-Version": "HTTP/1.1",
      "Status-Code": 200,
      "Reason-Phrase": "OK",
      "response_time_ms": 39,
      "payload": {
        "inlinedTextBody": "green open .plugins-ml-config        hfn4ZxQCQvOe0BN5TejuJA 1 0  1 0  3.9kb  3.9kb\ngreen open .opensearch-observability 7LKST3UWQDiNZyG3rnSqxg 1 0  0 0   208b   208b\ngreen open .opendistro_security      AvLAB1yDR4uk8PEQ212Yvg 1 0 10 0 78.3kb 78.3kb\n"
      }
    }
  ],
  "connectionId": "0242acfffe12000b-0000000a-00000011-657b97d8be126192-72df00be.0",
  "numRequests": 1,
  "numErrors": 0
}
```

</details>

### Capture Kafka Offloader

The Capture Kafka Offloader will act as a Kafka Producer for offloading captured traffic logs to the configured Kafka cluster.

Learn more about its functionality and setup here: [Capture Kafka Offloader](captureKafkaOffloader/README.md)

## Building

The building process for this project is streamlined through the use of Gradle. This section outlines the necessary steps to build the project and execute tests effectively.

To compile the project and execute unit tests, use the following command:

```sh
../gradlew build
```

This command compiles the source code and runs the quick unit tests, ensuring the project is correctly assembled and functional.

For a comprehensive test run, including both quick unit tests and more extensive slow tests, execute:

```sh
../gradlew allTests --rerun
```

This command initiates all tests, ensuring thorough validation of the project. The `--rerun` option is used to ignore existing task output cache for the specified tasks.

### Gradle Scans

Gradle Scans offer a more intuitive understanding of build outputs.
This action requires acceptance of the Gradle Scan terms of service.
To automate this acceptance and enable scans by default, set the `OS_MIGRATIONS_GRADLE_SCAN_TOS_AGREE_AND_ENABLED` environment variable:

```sh
export OS_MIGRATIONS_GRADLE_SCAN_TOS_AGREE_AND_ENABLED=
```

For persistent configuration in Zsh:

```sh
echo 'export OS_MIGRATIONS_GRADLE_SCAN_TOS_AGREE_AND_ENABLED=' >> ~/.zshrc
```

Access your detailed build reports by following the link provided at the end of your Gradle command's output.

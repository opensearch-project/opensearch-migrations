# Traffic Capture

## Table of Contents

- [Traffic Capture](#traffic-capture)
  - [Table of Contents](#table-of-contents)
  - [Overview](#overview)
  - [Tools](#tools)
    - [Docker Solution](#docker-solution)
    - [Traffic Capture Proxy Server](#traffic-capture-proxy-server)
    - [Traffic Replayer](#traffic-replayer)
    - [Migration Console](#migration-console)
      - [Understanding Data from the Replayer](#understanding-data-from-the-replayer)
    - [Capture Kafka Offloader](#capture-kafka-offloader)
  - [Building](#building)
    - [Gradle Scans](#gradle-scans)

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

TODO: When the Migration Console comes up use `console clusters run-benchmark` 

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

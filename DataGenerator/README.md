# Data Generator

This tool is used to generate data for testing a search cluster. The workloads are similar to those of [OpenSearch Benchmark](https://github.com/opensearch-project/OpenSearch-Benchmark).

> **⚠️ Test Infrastructure**  
> This tool is for test infrastructure. Features may change without notice, and backward compatibility is not guaranteed.

- [Data Generator](#data-generator)
  - [Workloads](#workloads)
    - [HttpLogs](#httplogs)
    - [Geonames](#geonames)
    - [Nested](#nested)
    - [NycTaxis](#nyctaxis)
  - [Run Data Generator](#run-data-generator)
    - [Run workloads programmatically](#run-workloads-programmatically)
    - [Generate data via Gradle](#generate-data-via-gradle)

## Workloads

The following workloads are supported and can be controlled with the `--workloads [workload1] [workload2] [...]`: `HttpLogs`, `Geonames`, `Nested`, `NycTaxis`.

### HttpLogs

Multiple indices with HTTP request log file entries that include client IP, timestamp, and request details.

### Geonames

A single index containing a list of geographic features from all over the world.

### Nested

A single index of Stack Overflow questions with user IDs and timestamps, along with answers containing user IDs and timestamps, using the nested mapping type.

### NycTaxis

A single index of taxi trip record data from every time a taxi dropped off a fare in the area.

## Run Data Generator

This tool can be used from the command line and programmatically. The programmatic approach is recommended.

### Run workloads programmatically

Insert the following code into the test case:

```java
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.data.WorkloadOptions;

// Create or use an existing OpenSearchClient
var client = new OpenSearchClient(...);

// Create an instance
var generator = new WorkloadGenerator(client);

// Pass workload options to the generate method, in this case using the defaults
generator.generate(new WorkloadOptions());
```

### Generate data via Gradle

To upload data onto a test cluster, use the following command:

```shell
./gradlew DataGenerator:run --args='--target-host http://hostname:9200'
```

<details>
<summary>
Example command output
</summary>

```
$ ./gradlew DataGenerator:run --args=' --target-host https://172.18.0.1:19200 --target-insecure --target-username admin --target-password admin --docs-per-workload-count 1000'

> Task :DataGenerator:run
2024-10-10 17:33:01,247 INFO o.o.m.u.ProcessHelpers [main] getNodeInstanceName()=generated_d0bf496d-1b80-4316-bf38-e3315321a3ef
2024-10-10 17:33:01,249 INFO o.o.m.DataGenerator [main] Starting DataGenerator with workerId=generated_d0bf496d-1b80-4316-bf38-e3315321a3ef
2024-10-10 17:33:01,552 INFO o.o.m.d.WorkloadGenerator [main] Starting document creation
2024-10-10 17:33:02,858 INFO o.o.m.d.WorkloadGenerator [main] All documents queued
2024-10-10 17:33:02,981 INFO o.o.m.d.WorkloadGenerator [main] All documents completed
2024-10-10 17:33:02,981 INFO o.o.m.DataGenerator [main] Generation complete, took 1,429.00ms

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/8.0.2/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 4s
25 actionable tasks: 1 executed, 24 up-to-date
```
</details>

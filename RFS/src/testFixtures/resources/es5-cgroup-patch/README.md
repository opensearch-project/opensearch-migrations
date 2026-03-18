# ES 5.1/5.2 cgroup v2 Patch

## What

Pre-compiled `OsProbe.class` and `OsProbe$OsProbeHolder.class` files that fix a cgroup v2
parsing bug in Elasticsearch 5.1 and 5.2.

## Why

ES 5.1/5.2's `OsProbe.getControlGroups()` fails to parse cgroup v2 lines (`0::/`) in
`/proc/self/cgroup`. This was fixed in ES 5.3 via the `es.cgroups.hierarchy.override`
system property. Since we can't use that property on 5.1/5.2, we patch the class file.

## Provenance

These `.class` files are the `OsProbe` class from **Elasticsearch 5.3.0**, which contains
the cgroup v2 fix. They were extracted from the official ES 5.3.0 distribution JAR:

```
elasticsearch-5.3.0/lib/elasticsearch-5.3.0.jar
  → org/elasticsearch/monitor/os/OsProbe.class
  → org/elasticsearch/monitor/os/OsProbe$OsProbeHolder.class
```

## How It's Used

`SearchClusterContainer.applyCgroupV2Patch()` copies these files into the ES 5.1/5.2
container at startup, replacing the broken OsProbe in the classpath before Elasticsearch
starts.

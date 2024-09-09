# Dashboard Sanitizer

### What is this tool?
If the Kibana objects are exported from 7.10.2 or later version, it may not be loaded into OpenSearch Dashboards successfully. This tool makes it compatible to OpenSearch Dashboards by fixing the version numbers for each kibana object and removes any incompatible objects. This does **NOT** attempt to convert non-compatible kibana objects like, *lens*, *canvas*, etc...   

### How to use this tool?

Clone the repository and navigate to the root directory of the repository.
```shell
git clone https://github.com/opensearch-project/opensearch-migrations.git
```

From the parent project, run the following command. The input file should be in *ndjson* format.
```shell
./gradlew dashboardsSanitizer:run --args='--source <<Your kibana object file location>> [--output <<output file location>>]'
```

#### Example
```shell
./gradlew dashboardsSanitizer:run --args='--source opensearch-export.ndjson --output sanitized.ndjson'
```

### Known limitations

* This has not been tested with Kibana version 8.7.0 onwards
* It removes the summary line in the output
* It does not attempt to convert non-compatible kibana objects like, *lens*, *canvas*, etc...

### Compatibility

|Saved Object Type|Supported Versions|Notes|
|---|---|---|
|dashboard|7.10.2 ... 8.8.0| lens, map, canvas-workpad, canvas-element, graph-workspace, connector, rule, action are not supported and will be omitted |
|visualization|7.10.2 ... 8.8.0| |
|search|7.10.2 ... 8.8.0| |
|index-pattern|7.10.2 ... 8.8.0| |
|url|7.10.2 ... 8.8.0| Only legacyUrl type is supported |
|query|7.10.2 ... 8.8.0| |

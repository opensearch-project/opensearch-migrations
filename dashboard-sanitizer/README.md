# Dashboard Sanitizer

### What is this tool?
If the Kibana objects are exported from 7.10.2 or later version, it may not be loaded into OpenSearch Dashboards successfully. This tool makes it compatible to OpenSearch Dashboards by fixing the version numbers for each kibana object and removes any incompatible objects. This does **NOT** attempt to convert non-compatible kibana objects like, *lens*, *canvas*, etc...   

### How to use this tool?

```shell
./gradlew dashboard-sanitzer:run --args='--source <<Your kibana object file location>> [--output <<output file location>>]'
```
### Known limitations

* This has not been tested with Kibana version 8.x onwards
* It removes the summary line in the output

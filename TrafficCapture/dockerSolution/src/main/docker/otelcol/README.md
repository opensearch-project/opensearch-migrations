# Generating the Base Docker Image

The base docker image currently relies on a custom build of the `otel-contrib-col` binary. This is because several of the features necessary are still under code review. Namely, those features are [adding OpenSearch as a logging export option](https://github.com/open-telemetry/opentelemetry-collector-contrib/pull/26475), and [promoting the OpenSearch Exporter to Alpha status](https://github.com/open-telemetry/opentelemetry-collector-contrib/pull/24668).

These two PRs have been manually merged in a local build, available [here](https://github.com/mikaylathompson/opentelemetry-collector-contrib/tree/opensearch-logs-exporter).

To generate the binary, suitable to be used on a docker container, in a location outside of this repo:
1. `git clone git@github.com:mikaylathompson/opentelemetry-collector-contrib.git`
2. `cd opentelemetry-collector-contrib`
3. `git checkout opensearch-logs-exporter`
4. `make install-tools`
5. `GOOS=linux GOARCH=amd64 make otelcontribcol`
6. `cp bin/otelcontribcol_linux_amd64 $MIGRATIONS_REPO/TrafficCapture/dockerSolution/src/main/docker/otelcol/`


Build a dockerfile like the following to copy the generated binary and a config file onto the container:

```
FROM openjdk:11-jre

COPY ./otelcontribcol_linux_amd64 otelcontribcol
RUN chmod +x otelcontribcol
COPY ./otel-config.yml /etc/otel-config.yml
CMD ./otelcontribcol --config /etc/otel-config.yml
```


## Moving past the custom build
When both of the above PRs have been merged into the `otel-contrib-col` mainline and are included in a release, it should be possible to switch from using this custom image to the standard publically released image, using the same config file.
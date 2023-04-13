# Datastash

## Overview

The purpose of these Docker configurations is to provide a simplified Logstash container that users can easily customize to migrate all indices from a source cluster (Elasticsearch by default) to a target cluster (OpenSearch by default).

## Instructions

### 0. Prerequisites

* [curl](https://curl.se/)
* [jq](https://stedolan.github.io/jq/)

### 1. Update the Logstash configuration file

Edit the `logstash.conf` configuration file to point to your source and target `hosts`. Make sure to also add [any other settings](https://github.com/opensearch-project/logstash-output-opensearch) that are appropriate, such as  authentication (`user` and `password`) and `ssl`. Typically, you will not need to change any of the other settings - they are aimed at moving all indices from the source to the target, retaining their names as-is.

### 2. Configure index templates on the target cluster

By default, the `logstash.conf` configuration file used by Datastash creates an index on the target cluster with the _same name_ as the source cluster. To ensure that index settings and mappings are correctly configured, perform the following steps _for each index that will be migrated_:

1. Form the index template by running the following command, replacing `<index-name>` with the name of the index being migrated:

```
indexName=<index-name>; curl -s http://ec2-18-236-163-55.us-west-2.compute.amazonaws.com:8443/$indexName | jq --arg INDEXNAME "$indexName" '{index_patterns: [$INDEXNAME], priority: 500, template: {settings: .[$INDEXNAME].settings, mappings: .[$INDEXNAME].mappings}} | del(.template.settings.index.creation_date, .template.settings.index.uuid, .template.settings.index.provided_name, .template.settings.index.version)' > /tmp/datastash_template.json
```

2. Update the target cluster with the index template, replacing `<index-name>` with the name of the index being migrated:

```
indexName=<index-name>; curl -XPUT -H 'Content-Type: application/json' "<target>:<port>/_index_template/datastash_template_$indexName?pretty" -d @/tmp/datastash_template.json
```

3. Finally, clean up the temporary file we created:

```
rm /tmp/datastash_template.json
```

### 3. Run the migration

You're finally ready to kick off the migration! Move your data by running `docker compose up`. Once the process completes, the docker container will shut itself down.

# Limitations

This tool has several limitations:

1. Logstash does not include any support for clustering or load balancing ([[1]](https://discuss.elastic.co/t/how-can-make-a-cluster-with-logstash-is-it-possible/250444/2), [[2]](https://github.com/elastic/logstash/issues/2632), [[3]](https://github.com/elastic/logstash/issues/2633)), so users must set up their own load balancing. This makes it impossible to horizontally scale a Logstash setup that uses a pull-based plugin like the ElasticSearch/OpenSearch input plugin.

1. Since Logstash cannot make any assumptions about the input or output, it cannot perform any setup steps before sending data to the target cluster, and the validation it performs is minimal.
    * Logstash does not validate the configuration of the output plugin. Any mistakes in the OpenSearch output plugin configuration (endpoint, username/password, SSL, etc.) do not fail the Logstash pipeline immediately. Instead, Logstash will continue to process the input (until complete), and then fail each batch at the output.
    * Contrary to the output plugin, Logstash does validate connectivity from the input plugin. Any errors therein cause the pipeline to stop processing completely. However, since Logstash cannot make any assumptions about the input, it does not check for a index of the same name on the target cluster.
    * Further, these decisions mean that index mappings are not copied to the target cluster, which necessitates the manual steps outlined above.

1. Logstash also implicitly adds two internal fields to each record in its output - “@version” and “@timestamp”. There is no way to prevent Logstash from adding these fields. The suggested workaround is to apply a mutate filter to remove these fields, but such an approach would remove these fields even if they existed in the original index.

1. Logstash versions above 7.14 do not support OSS endpoints due to a [change introduced to the ElasticSearch clients](https://discuss.elastic.co/t/logstash-not-start-after-upgrade-to-new-version/291849/2). In order to work around this restriction, this tool uses an older image ([7.13.4](https://hub.docker.com/layers/opensearchproject/logstash-oss-with-opensearch-output-plugin/7.13.4/images/sha256-a0370926a62f5a81078e24f9acb37e6750ee2bc8472a37b071c40b6765319ea5?context=explore)) from Dockerhub. This prevents the client from receiving any further updates, which risks breaking compatibility with future versions of ElasticSearch.

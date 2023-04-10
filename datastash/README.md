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

To ensure that the settings and mappings are correctly configured for the indices being migrated, perform the following steps _for each index being migrated_:

1. In the `index_template_base.json` file, update the `index_patterns` array with the name of the index being migrated:
```
...
    "index_patterns": ["myIndex"]
}
```

2. Fetch the settings for the index by executing the following command, replacing `<index-name>` with the name of the index being migrated:

```
curl -s <source>:<port>/<index-name>/_settings | jq '.<index-name>.settings | del(.index.creation_date, .index.uuid, .index.provided_name, .index.version.created) | {template: {settings: .}}' > /tmp/settings.json
```

3. Fetch the mappings for the index by executing the following command, replacing `<index-name>` with the name of the index being migrated:

```
curl -s <source>:<port>/<index-name>/_mappings | jq '{template: {mappings: .<index-name>.mappings}}' > /tmp/mappings.json
```

4. Combine the JSON files to form the index template:

```
jq -s '.[0] * .[1] * .[2]' index_template_base.json /tmp/settings.json /tmp/mappings.json > /tmp/datastash_template.json
```

5. Update the target cluster with the index template, replacing `<index-name>` with the name of the index being migrated:

```
curl -XPUT -H 'Content-Type: application/json' "<target>:<port>/_index_template/datastash_template_<index-name>?pretty" -d @/tmp/datastash_template.json
```

6. Finally, clean up the temporary files we created:

```
rm /tmp/settings.json /tmp/mappings.json 
```

### 3. Run the migration

You're finally ready to kick off the migration! Move your data by running `docker compose up`. Once the process completes, the docker container will shut itself down.
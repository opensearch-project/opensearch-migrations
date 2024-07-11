# Metadata Migration

When moving between different kinds of clusters there is moving raw data, and there is moving the containers and support materials that are part of that ecosystem.  Metadata migration is a part of the overall lifecycle, and it exists in the start of the migration process to ensure the data goes to the expected destination. 

## Migration Lifecycle

To handle moving more than data OpenSearch Migration includes we are tooling to inspect an existing cluster, generate a recommended configuration, and apply the migration onto a target cluster.

```mermaid
graph LR
    A[Evaluate] --> B[Configure]
    B --> C[Verify]
    C --> A
    C --> D[Deploy]
    D --> E[Audit]
    E -.-> F([Start Data Migration])
```

### Evaluate
By inspecting the source cluster and the target cluster, the metadata tooling will determine what items can be processed and what items need more detail to migration successfully.

A target cluster is not required to perform an evaluation by provided a target cluster version to the tool. 

### Configure
After inspecting the kind of migration that is underway there are different options, such as which indices to move to the target cluster, down to more advanced options such as how to handle deprecated features.  By updating the command line arguments or configuration file you choose how to perform the migration.

### Verify
After filling out the configuration, run the verify process to make sure that all scenarios are handled and that no configuration errors or missing settings are present.  If there is a warning or error, you might revisit the evaluation process and repeating until everything is shaped as expected.

### Deploy
The metadata migration tool will fully read the source cluster and recreate the supported features on the target cluster.

### Audit
Inspect the transformation logs (tuple logs), or run queries against the live target cluster.  Before the long process moving data from the source to the target check on anything that needs to be working to prevent redriving.

## Walkthrough

### Basic source cluster inspection

#### Configure meta to connect to the source cluster
```
console meta configure -source \
    --source-host http://localhost:19200 \
    --source-username admin \
    --source-password admin \

Result:
   OK - able to connect to source cluster
```

#### Evaluate the migration status
```
console meta evaluate

Clusters:
   Source:
      Version: Elasticsearch 7.10.2
      Url: http://localhost:9200
      WARN - Elasticsearch 7.10.2 is not specifically supported, will attempt to migrate with as if Elasticsearch 7.17.22

Migration Candidates:
   Indexes:
      geonames, logs-181998, logs-191998, logs-201998, logs-211998
      INFO - 12 More not shown, use add `--full` to see all results

   Index Templates:
      daily_logs

   Component Templates:
      <None Found>

   Aliases:
      logs-all

Result:
   1 migration issues detected

Issues:
   No target cluster / target version specified, add with `console meta configure -target`
```


#### Evaluate the migration status with full details
```
console meta evaluate --full

Clusters:
   Source:
      Version: Elasticsearch 7.10.2
      Url: http://localhost:9200

Migration Candidates:
   Indexes:
      geonames, logs-181998, logs-191998, logs-201998, logs-211998, logs-221998, logs-231998, logs-241998, nyc_taxis, reindexed-logs, sonested
...
```

### Evaluate with transformation details

#### Configure meta to connect to the target cluster
```
console meta configure -target \
    --source-host http://localhost:19200 \
    --source-username admin \
    --source-password admin \

Result:
   OK - able to connect to target cluster
```

#### Evaluate the migration status
```
console meta evaluate

Clusters:
   Source:
      Version: Elasticsearch 7.10.2
      Url: http://localhost:9200
      WARN - Elasticsearch 7.10.2 is not specifically supported, will attempt to migrate with as if Elasticsearch 7.17.22

   Target:
      Version: OpenSearch 2.11.0
      Url: http://localhost:19200

Migration Candidates:
   Indexes:
      geonames, logs-181998, logs-191998, logs-201998, logs-211998
      INFO - 12 More not shown, add `--full` to see all results

   Index Templates:
      daily_logs

   Component Templates:
      <None Found>

   Aliases:
      logs-all

Transformations:
   Index:
      ERROR - IndexMappingTypeRemoval is Unsupported on Index `logs-181998` "Multiple mapping types are not supported""
   Index Template:
      ERROR - IndexMappingTypeRemoval is Unsupported on Index Template `daily_logs` "Multiple mapping types are not supported"
   DEBUG - 6 transformations did not apply, add --`full` to see all results

Result:
   2 migration issues detected

Issues:
   IndexMappingTypeRemoval is Unsupported on Index `logs-181998` "Multiple mapping types are not supported""
   IndexMappingTypeRemoval is Unsupported on Index Template `daily_logs` "Multiple mapping types are not supported"
```
### Exclude incompatible rolling logs indices

#### Configure meta to only allow certain indexes
```
console meta configure -index \
   --allow-list geonames, nyc_taxis, sonested

Result:
    Ok - allow-list is geonames, nyc_taxis, sonested
```

#### Configure meta to deny the incompatible template
```
console meta configure -index-template \
   --deny-list daily_logs

Result:
    Ok - deny-list is daily_logs
```

#### Evaluate meta with the updated configuration 

```
console meta evaluate

Clusters:
   Source:
      Version: Elasticsearch 7.10.2
      Url: http://localhost:9200
      WARN - Elasticsearch 7.10.2 is not specifically supported, will attempt to migrate with as if Elasticsearch 7.17.22

   Target:
      Version: OpenSearch 2.11.0
      Url: http://localhost:19200

Migration Candidates:
   Indexes:
      geonames, nyc_taxis, sonested
      INFO - 5 items excluded due to allow and/or deny list

   Index Templates:
      <None Found>
      INFO - 1 items excluded due to allow and/or deny list

   Component Templates:
      <None Found>

   Aliases:
      logs-all

Transformations:
   Index:
      IndexMappingTypeRemoval - Will Apply - Learn more http://kb.migrations.opensearch.org/1001/

   DEBUG - 6 transformations did not apply, add --`full` to see all results

Result:
   No migration issues detected
```

### Deploy meta

```
console meta deploy \
   --no-confirmation

Clusters:
   Source:
      Version: Elasticsearch 7.10.2
      Url: http://localhost:9200
      WARN - Elasticsearch 7.10.2 is not specifically supported, will attempt to migrate with as if Elasticsearch 7.17.22

   Target:
      Version: OpenSearch 2.11.0
      Url: http://localhost:19200

Migrated Items:
   Indexes:
      geonames, nyc_taxis, sonested

   Aliases:
      logs-all

Transformations:
   Index:
      IndexMappingTypeRemoval - Applied on 3 items

Result:
   No migration issues detected
   INFO - See full deployment log at `/tmp/meta_deploy_1720710618.log`

```

## Design Details

### Configuration
Configuration data should be stored in a way that mirrors the existing [Services.yaml spec](../TrafficCapture/dockerSolution/src/main/docker/migrationConsole/lib/console_link/README.md)

This ensures that if the tool is run locally its setup can be deployed to the cloud with minimal effort.

### Console response structure

The output of the meta tool is to provide a top-to-bottom list of what is relevant to a migration. Overall command line response is structured as follows 
```
Clusters:
   ...
Migration Candidates | Migrated Items:
   ...
Transformations:
   ...
Result:
   ...
Issues:
   ...
```

If there are any DEBUG/INFO/WARN/ERROR messages they are printed at in the highest level bucket available.  ERROR messages are all captured and repeated under issues at the bottom of the output.

Each section is indented in the same amount, and if there are subsections those receive their own level of indenting.

```
Migration Candidates:
   Indexes:
      my-index, your-index
   Plugins:
      Security:
         Authentication:
            OIDCProvider
```

#### Transformations

Transformations should be printed out by what components they are applied to, their name, and where to get more detailed information on their function.  The following is an `index` transformation named `IndexMappingTypeRemoval` that `will` be applied that can be referred on `http://kb.migrations.opensearch.org/1001/`

```
Transformations:
   Index:
      IndexMappingTypeRemoval - Will Apply - Learn more http://kb.migrations.opensearch.org/1001/
```

#### Exit codes

0 for success, otherwise failure with the code being the number of issues.
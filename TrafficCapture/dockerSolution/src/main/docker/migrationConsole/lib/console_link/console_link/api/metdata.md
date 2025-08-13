### Example response:

```json
{
  "clusters" : {
    "source" : {
      "type" : "Snapshot",
      "version" : "ELASTICSEARCH 5.6.0",
      "localRepository" : "/snapshot"
    },
    "target" : {
      "type" : "Remote Cluster",
      "version" : "OPENSEARCH 2.19.1",
      "uri" : "https://opensearch-cluster-master:9200",
      "protocol" : "HTTPS",
      "insecure" : true,
      "awsSpecificAuthentication" : false,
      "disableCompression" : false
    }
  },
  "items" : {
    "dryRun" : true,
    "indexTemplates" : [ {
      "name" : "watches",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".monitoring-es",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".monitoring-alerts",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".watch-history-6",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".ml-notifications",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".monitoring-kibana",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : "security-index-template",
      "successful" : true
    }, {
      "name" : ".ml-state",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".monitoring-logstash",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : ".ml-meta",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : "triggered_watches",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : "security_audit_log",
      "successful" : true
    }, {
      "name" : ".ml-anomalies-",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    } ],
    "componentTemplates" : [ ],
    "indexes" : [ {
      "name" : ".monitoring-es-6-2025.08.05",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    }, {
      "name" : "test_0004_hqbl-20250805232705",
      "successful" : false,
      "failure" : {
        "type" : "ALREADY_EXISTS",
        "message" : "already exists",
        "fatal" : false
      }
    }, {
      "name" : ".watches",
      "successful" : false,
      "failure" : {
        "type" : "SKIPPED_DUE_TO_FILTER",
        "message" : "skipped due to filter",
        "fatal" : false
      }
    } ],
    "aliases" : [ ],
    "errors" : [ ]
  },
  "transformations" : {
    "transformers" : [ {
      "name" : "Field Data Type Deprecation - string",
      "description" : [ "Convert field data type string to text/keyword" ]
    }, {
      "name" : "Version Transform",
      "description" : [ "Other transforms for source to target shape conversion" ]
    } ]
  },
  "errors" : [ ],
  "errorCode" : 0
}
```
# Overlays CDC traffic configuration (Kafka, capture-proxy, replayer) onto a base
# migration config. Used by cdcMigrationImportedClusters.yaml.
#
# Input: base OVERALL_MIGRATION_CONFIG (from fullMigrationImportedClusters)
# Output: base config + kafkaClusterConfiguration + traffic section
# Usage: echo "$BASE_CONFIG" | jq -f addCdcTrafficConfig.jq

. + {
  "kafkaClusterConfiguration": {
    "default": {
      "autoCreate": {}
    }
  },
  "traffic": {
    "proxies": {
      "capture-proxy": {
        "source": "source1",
        "proxyConfig": {
          "listenPort": 9201,
          "noCapture": true
        }
      }
    },
    "replayers": {
      "replay1": {
        "fromProxy": "capture-proxy",
        "toTarget": "target1",
        "dependsOnSnapshotMigrations": [
          {"source": "source1", "snapshot": "testsnapshot"}
        ],
        "replayerConfig": {
          "observedPacketConnectionTimeout": 30,
          "speedupFactor": 20
        }
      }
    }
  }
}

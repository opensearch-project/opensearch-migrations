# Solr to OpenSearch Sizing Steering

## Heuristics
- Shard Count: Generally, aim for 1-2 OpenSearch shards per Solr shard, depending on volume. Keep shard sizes between 10GB and 50GB.
- Replica Factor: Standard `number_of_replicas: 1` (total 2 copies) for production.
- JVM Heap: OpenSearch heap should be around 50% of available RAM, up to 32GB (pointer compression).
- CPU/RAM: Start with a 1:1 resource mapping (CPU/RAM) from Solr nodes to OpenSearch nodes as a baseline.

## Topologies
- SolrCloud (ZooKeeper) → OpenSearch (Cluster Manager).
- No external ZooKeeper needed for OpenSearch.

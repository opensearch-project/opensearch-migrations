#!/usr/bin/env bash

# check if any valid segment file names are contained in the response. If its a generic SegmentInfos or segments or such
# means its empty
strings backups/solrcloud/nyc_taxis_6/snapshot.shard1/segments_5
strings backups/solrcloud/nyc_taxis_6/snapshot.shard2/segments_5
strings backups/solrcloud/nyc_taxis_6/snapshot.shard3/segments_5

# check backup index validity
docker exec solr-node1 java -cp "/opt/solr/server/solr-webapp/webapp/WEB-INF/lib/*" org.apache.lucene.index.CheckIndex /backups/solrcloud/nyc_taxis_6/snapshot.shard1/
docker exec solr-node1 java -cp "/opt/solr/server/solr-webapp/webapp/WEB-INF/lib/*" org.apache.lucene.index.CheckIndex /backups/solrcloud/nyc_taxis_6/snapshot.shard2/
docker exec solr-node1 java -cp "/opt/solr/server/solr-webapp/webapp/WEB-INF/lib/*" org.apache.lucene.index.CheckIndex /backups/solrcloud/nyc_taxis_6/snapshot.shard3/

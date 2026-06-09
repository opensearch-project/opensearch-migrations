#!/bin/bash

set -e

SOLR_XML_FILE="/opt/solr/server/solr-template.solr.xml"
ZK_CONFIG_FILE="/opt/solr/server/solr-template.zoo.cfg"

[ -f "$SOLR_HOME/solr.xml" ] || { echo "Copying solr.xml from  $SOLR_XML_FILE to $SOLR_HOME/solr.xml"; cp "$SOLR_XML_FILE" "$SOLR_HOME/solr.xml"; }
[ -f "$SOLR_HOME/zoo.cfg" ]  || { echo "Copying zoo.cfg from $ZK_CONFIG_FILE to $SOLR_HOME/zoo.cfg"; cp $ZK_CONFIG_FILE  "$SOLR_HOME/zoo.cfg"; }

# snippet for making sure termination signal reaches solr
SOLR_PID=
term() { kill -TERM "$SOLR_PID" 2>/dev/null; wait "$SOLR_PID"; }
trap term TERM INT

solr start -DzkRun &
SOLR_PID=$!

# wait until solr is ready
until curl -fsS http://localhost:8983/solr/admin/info/system >/dev/null 2>&1; do
  sleep 1
done

# Upload every configset under /opt/solr/server/solr/configsets/ that isn't already in ZK
for dir in /opt/solr/server/solr/configsets/*/; do
  name=$(basename "$dir")
  echo "Ensuring configset $name has been uploaded"
  if ! curl -fsS "http://localhost:8983/solr/admin/configs?action=LIST&wt=json" \
       | jq -e --arg n "$name" '.configSets | index($n)' >/dev/null; then
    tmp=$(mktemp).zip
    (cd "$dir/conf" && zip -qr "$tmp" .)
    curl -fsS -X POST -H 'Content-Type: application/octet-stream' \
      --data-binary "@$tmp" \
      "http://localhost:8983/solr/admin/configs?action=UPLOAD&name=$name"
    rm -f "$tmp"
  fi
done

# wait until background solr exits
wait "$SOLR_PID"

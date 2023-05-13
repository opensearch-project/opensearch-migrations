#!/bin/bash

# Temporary measure, if we pursue this longer term should see about properly passing file
echo $LOGSTASH_CONFIG > logstash.conf
sed -i 's/PUT_LINE/\n/g' logstash.conf

# ECS does not have clear convention for running a task only once, again if pursued longer term
# we should do something other than stall on success and retry on failure
if /usr/share/logstash/bin/logstash -f logstash.conf ; then
  while true
  do
    echo "Logstash migration finished successfully"
    sleep 600
  done
else
  echo "Logstash migration has failed. Will relaunch shortly..."
  sleep 60
fi
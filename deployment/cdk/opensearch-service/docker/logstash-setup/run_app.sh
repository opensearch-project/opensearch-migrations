#!/bin/bash

# Temporary measure, if we pursue this longer term should see about properly passing file
echo $LOGSTASH_CONFIG > logstash.conf
sed -i 's/PUT_LINE/\n/g' logstash.conf

/usr/share/logstash/bin/logstash -f logstash.conf

#while true
#do
#  sleep 5
#done
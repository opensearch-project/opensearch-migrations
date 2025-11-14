#!/bin/sh
exec java -XX:MaxRAMPercentage=80.0 -cp /rfs-app/jars/*:. "$@"

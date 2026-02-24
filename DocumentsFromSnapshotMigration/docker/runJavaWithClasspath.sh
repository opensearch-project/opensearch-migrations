#!/bin/sh
# Application defaults — overridable via JDK_JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-XX:MaxRAMPercentage=80.0"

exec java -cp /rfs-app/jars/*:. "$@"

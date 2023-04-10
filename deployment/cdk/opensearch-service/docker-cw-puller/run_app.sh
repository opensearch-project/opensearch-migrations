#!/bin/bash

# Pipe output to STDERR as well as to port with netcat
while true
do
  python3 -u pull_then_poll_cw_logs.py | tee /dev/stderr | nc localhost 9210
  >&2 echo "Command has encountered error. Restarting now ..."
  sleep 5
done
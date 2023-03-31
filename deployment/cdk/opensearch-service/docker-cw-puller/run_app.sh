#!/bin/bash

# Pipe output to STDOUT as well as to port with netcap
python3 -u pull_then_poll_cw_logs.py | tee >(nc -v -l -p 9210)
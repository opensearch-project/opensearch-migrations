#!/bin/bash

dataset=$1

if [[ "$dataset" == "default_osb_test_workloads" ]]; then
   generate_data_requests $2
elif [[ "$dataset" == "skip_dataset" ]]; then
   echo "Skipping data generation step"
   mkdir -p /usr/share/elasticsearch/data/nodes
else
   echo "Unknown dataset provided: ${dataset}"
   exit 1;
fi
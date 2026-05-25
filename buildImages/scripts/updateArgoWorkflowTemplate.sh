#!/bin/bash

set -eo pipefail

kubectl apply -f ../migrationConsole/lib/integ_test/testWorkflows/clusterWorkflows.yaml -n ma
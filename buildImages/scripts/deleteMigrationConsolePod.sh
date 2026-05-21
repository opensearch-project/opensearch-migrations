#!/bin/bash

set -eo pipefail

kubectl delete pod migration-console-0 -n ma
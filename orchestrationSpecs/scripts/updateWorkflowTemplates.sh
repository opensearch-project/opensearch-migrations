#!/usr/bin/env bash

set -e

# Create a temporary file
TEMPORARY_FILE=$(mktemp -d)

# Ensure cleanup on exit
trap "rm -rf $TEMPORARY_FILE" EXIT

kc delete workflowtemplates `kc get workflowtemplates 2>&1 | tail -n +2  | grep -v "No resources"  | cut -f 1 -d \  `
npm run make-templates -- --outputDirectory ${PWD}/${TEMPORARY_FILE} && kc create -f ${TEMPORARY_FILE}

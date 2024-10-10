#! /bin/bash

# Fail the script if any command fails
set -e

# Discussion needed: Container will have a minimum number of
# parameters after on start services.yaml is loaded.
#
# This script was parsing the parameters that were feed into the
# container, unless this script is pulling parameters from the
# services.yaml its going to be out of sync. 
#
# Metadata & Snapshot will already need to know how to read the 
# Secrets from ARNs so this seems like it aligns well to do this
# at the same time.

# Discussion needed: Directly cleanup was being done by script based
# on arguments
#
# Alternative, java can clean these directories on start since this script 
# won't be able to resolve the path(s) without parsing the services.yaml

[ -z "$RFS_COMMAND" ] && \
{ echo "Warning: RFS_COMMAND is empty! Exiting."; exit 1; } || \
until ! {
    echo "Running command $RFS_COMMAND"
    eval "$RFS_COMMAND"
}; do
    echo "About to start the next run."
done

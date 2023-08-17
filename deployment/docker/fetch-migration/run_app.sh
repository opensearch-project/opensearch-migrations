#!/bin/bash

# Temporary measure, if we pursue this longer term should see about properly passing file
echo "$INLINE_PIPELINE" | base64 -d  > $ICT_CODE_PATH/input.yaml
# Duplicates the Fetch-Migration Dockerfile entrypoint
python -u ./orchestrator.py $DATA_PREPPER_PATH $ICT_CODE_PATH/input.yaml https://localhost:4900

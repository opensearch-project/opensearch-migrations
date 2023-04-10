#!/bin/bash

# IF the user cares about the diffs.log file, then another command to save it somewhere can be inserted here.
# AND, the user can use the "--export-reports DiffReport" option of the trafficcomparator

while true
do
  nc -v -l -p 9220 | tee /dev/stderr | trafficcomparator -v stream | trafficcomparator stream-report
  >&2 echo "Command has encountered error. Restarting now ..."
  sleep 1
done


# Next steps: Save the diffs.log somewhere. (Again, if the user needs them)
# In this case, we will need to add the part of the command mentioned above to save the diff logs e.g
# "--export-reports DiffReport diffs.log".
# So it's either you include the full command AND insert another command here to save the file, or neither of them.
# The command to save the diffs.log can to save it to either a shared volume (which is docker and fargate compatible),
# S3 bucket, or whichever works and is a more convenient solution for the user.
# A task was created to add this command (https://opensearch.atlassian.net/browse/MIGRATIONS-1043)

#!/bin/bash

nc -l -p 9220 | trafficcomparator -v stream | trafficcomparator stream-report --export-reports DiffReport diffs.log

# IF the user cares about the diffs.log file, then another command to save it somewhere can be inserted here.
# Otherwise, this part of the above command "--export-reports DiffReport diffs.log" isn't needed, neither is the
# additional command to save it (see below)

# Next steps: Save the diffs.log somewhere. (Again, if the user needs them)
# In this case, we still need this part of the command that runs the comparator "--export-reports DiffReport diffs.log".
# So it's either you include the full command AND insert another command here to save the file, or neither of them.
# The command to save the diffs.log can to save it to either a shared volume (which is docker and fargate compatible),
# S3 bucket, or whichever works and is a more convenient solution for the user.
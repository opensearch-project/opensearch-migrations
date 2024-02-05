#!/bin/bash

usage() {
  echo ""
  echo "Script to display the AWS CLI command needed to execute a Fetch Migration, based on provided options."
  echo ""
  echo "Usage: "
  echo "  ./showFetchMigrationCommand.sh <>"
  echo ""
  echo "Options:"
  echo "  --create-only                            Skips data migration and only creates indices on the target cluster."
  echo "  --dryrun                                 Performs a dry-run. Only a report is printed - no indices are created or migrated."
  echo ""
  exit 1
}

while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --create-only)
            create_only=true
            shift
            ;;
        --dryrun)
            dry_run=true
            shift
            ;;
        -h|--h|--help)
            usage
            ;;
        -*)
            echo "Unknown option $1"
            usage
            ;;
        *)
            shift
            ;;
    esac
done

# Ensure Fetch Migration command is available before proceeding
if [ -z "$FETCH_MIGRATION_COMMAND" ]; then
    echo "Fetch Migration is unavailable or not deployed as required FETCH_MIGRATION_COMMAND environment variable is missing, exiting..."
    exit 1
fi

# ECS command overrides argument with placeholder for flags
OVERRIDES_ARG="--overrides '{ \"containerOverrides\": [ { \"name\": \"fetch-migration\", \"command\": <FLAGS> }]}'"

# Default values
create_only=false
dry_run=false

# Build flags string
flags=""
if [ "$dry_run" = true ]; then
    flags="--dryrun,${flags}"
fi
if [ "$create_only" = true ]; then
    flags="--createonly,${flags}"
fi

command_to_run="$FETCH_MIGRATION_COMMAND"
# Only add overrides suffix if any flags were specified
if [ -n  "$flags" ]; then
    # Remove trailing ,
    flags=${flags%?}
    # Convert to JSON array string
    flags=$(echo -n $flags | jq -cRs 'split("\n")')
    # Replace placeholder value in overrides string with actual flags
    command_to_run="$command_to_run ${OVERRIDES_ARG/<FLAGS>/$flags}"
fi

echo $command_to_run

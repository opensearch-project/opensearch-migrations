#!/bin/bash

# Check if the environment variable MIGRATION_SERVICES_YAML_PARAMETER is set
if [ -z "$MIGRATION_SERVICES_YAML_PARAMETER" ]; then
  echo "Environment variable MIGRATION_SERVICES_YAML_PARAMETER is not set. Exiting successfully and "
  echo "assuming the migration services yaml is already in place."
  exit 0
fi

# Retrieve the parameter value from AWS Systems Manager Parameter Store
PARAMETER_VALUE=$(pipenv run aws ssm get-parameters --names "$MIGRATION_SERVICES_YAML_PARAMETER" --query "Parameters[0].Value" --output text)

# Check if the retrieval was successful
if [ $? -ne 0 ]; then
  echo "Failed to retrieve parameter: $MIGRATION_SERVICES_YAML_PARAMETER"
  exit 1
fi

# Define the output file path
OUTPUT_FILE="/etc/migration_services.yaml"

# Write the parameter value to the file
echo "$PARAMETER_VALUE" > "$OUTPUT_FILE"

# Check if the write was successful
if [ $? -ne 0 ]; then
  echo "Failed to write to file: $OUTPUT_FILE"
  exit 1
fi

echo "Parameter value successfully written to $OUTPUT_FILE"

# Generate bash completion script
console completion bash > /usr/share/bash-completion/completions/console

# Source the completion script to enable it for the current session
source /usr/share/bash-completion/completions/console

# Add sourcing of the completion script to .bashrc for persistence across sessions
echo '. /etc/bash_completion' >> ~/.bashrc

echo "Bash completion for console command has been set up and enabled."

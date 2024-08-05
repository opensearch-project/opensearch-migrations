#! /bin/bash

# Fail the script if any command fails
set -e

# Print our ENV variables
echo "RFS_COMMAND: $RFS_COMMAND"
echo "RFS_TARGET_USER: $RFS_TARGET_USER"
echo "RFS_TARGET_PASSWORD_ARN: $RFS_TARGET_PASSWORD_ARN"

# Check if the RFS Command already contains a password; only do special work in if does not
if [[ $RFS_COMMAND != *"--target-password"* ]]; then
    PASSWORD_TO_USE=""

    # Check if the password is available in plaintext; if, use it.  Otherwise, retrieve it from AWS Secrets Manager
    if [[ -n "$RFS_TARGET_PASSWORD" ]]; then
        echo "Using plaintext password from ENV variable RFS_TARGET_PASSWORD"
        PASSWORD_TO_USE="$RFS_TARGET_PASSWORD"
    else
        echo "Using password from AWS Secrets Manager"
        PASSWORD_TO_USE=$(aws secretsmanager get-secret-value --secret-id $RFS_TARGET_PASSWORD_ARN --query SecretString --output text)
    fi

    # Append the password to the RFS Command
    echo "Updated RFS Command with username/password"
    RFS_COMMAND="$RFS_COMMAND --target-username $RFS_TARGET_USER --target-password $PASSWORD_TO_USE"
fi

echo "Executing RFS Command"
eval $RFS_COMMAND
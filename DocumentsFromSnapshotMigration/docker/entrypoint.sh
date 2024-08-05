#! /bin/bash

# Fail the script if any command fails
set -e

# Print our ENV variables
if [[ $RFS_COMMAND != *"--target-password"* ]]; then
    echo "RFS_COMMAND: $RFS_COMMAND"
else
    echo "RFS Target Cluster password found in RFS_COMMAND; skipping logging of the value"
fi

echo "RFS_TARGET_USER: $RFS_TARGET_USER"
echo "RFS_TARGET_PASSWORD: <redacted>"
echo "RFS_TARGET_PASSWORD_ARN: $RFS_TARGET_PASSWORD_ARN"

# Check if the RFS Command already contains a username; only do special work if it does not
if [[ $RFS_COMMAND != *"--target-username"* ]]; then
    if [[ -n "$RFS_TARGET_USER" ]]; then
        echo "Using username from ENV variable RFS_TARGET_USER.  Updating RFS Command with username."
        RFS_COMMAND="$RFS_COMMAND --target-username $RFS_TARGET_USER"
    fi
fi

# Check if the RFS Command already contains a password; only do special work if it does not
if [[ $RFS_COMMAND != *"--target-password"* ]]; then
    PASSWORD_TO_USE=""

    # Check if the password is available in plaintext; if, use it.  Otherwise, retrieve it from AWS Secrets Manager
    if [[ -n "$RFS_TARGET_PASSWORD" ]]; then
        echo "Using plaintext password from ENV variable RFS_TARGET_PASSWORD"
        PASSWORD_TO_USE="$RFS_TARGET_PASSWORD"
    elif [[ -n "$RFS_TARGET_PASSWORD_ARN" ]]; then
        # Retrieve password from AWS Secrets Manager if ARN is provided
        echo "Using password from AWS Secrets Manager ARN in ENV variable RFS_TARGET_PASSWORD_ARN"
        PASSWORD_TO_USE=$(aws secretsmanager get-secret-value --secret-id "$RFS_TARGET_PASSWORD_ARN" --query SecretString --output text)
    fi

    # Append the username/password to the RFS Command if have an updated password
    if [[ -n "$PASSWORD_TO_USE" ]]; then
        echo "Updating RFS Command with password."
        RFS_COMMAND="$RFS_COMMAND --target-password $PASSWORD_TO_USE"
    fi
fi

echo "Executing RFS Command"
eval $RFS_COMMAND
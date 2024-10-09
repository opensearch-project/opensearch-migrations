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
        RFS_COMMAND="$RFS_COMMAND --target-username \"$RFS_TARGET_USER\""
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
        RFS_COMMAND="$RFS_COMMAND --target-password \"$PASSWORD_TO_USE\""
    fi
fi

# Extract the value passed after --s3-local-dir
S3_LOCAL_DIR=$(echo "$RFS_COMMAND" | sed -n 's/.*--s3-local-dir\s\+\("[^"]\+"\|[^ ]\+\).*/\1/p' | tr -d '"')
# Extract the value passed after --lucene-dir
LUCENE_DIR=$(echo "$RFS_COMMAND"  | sed -n 's/.*--lucene-dir\s\+\("[^"]\+"\|[^ ]\+\).*/\1/p' | tr -d '"')
if [[ -n "$S3_LOCAL_DIR" ]]; then
    echo "Will delete S3 local directory between runs: $S3_LOCAL_DIR"
else
    echo "--s3-local-dir argument not found in RFS_COMMAND. Will not delete S3 local directory between runs."
fi

if [[ -n "$LUCENE_DIR" ]]; then
    echo "Will delete lucene local directory between runs: $LUCENE_DIR"
else
    echo "--lucene-dir argument not found in RFS_COMMAND. This is required."
    exit 1
fi

cleanup_directories() {
    if [[ -n "$S3_LOCAL_DIR" ]]; then
        echo "Cleaning up S3 local directory: $S3_LOCAL_DIR"
        rm -rf "$S3_LOCAL_DIR"
        echo "Directory $S3_LOCAL_DIR has been cleaned up."
    fi

    if [[ -n "$LUCENE_DIR" ]]; then
        echo "Cleaning up Lucene local directory: $LUCENE_DIR"
        rm -rf "$LUCENE_DIR"
        echo "Directory $LUCENE_DIR has been cleaned up."
    fi
}



[ -z "$RFS_COMMAND" ] && \
{ echo "Warning: RFS_COMMAND is empty! Exiting."; exit 1; } || \
until ! {
    echo "Running command $RFS_COMMAND"
    eval "$RFS_COMMAND"
}; do
    echo "Cleaning up directories before the next run."
    cleanup_directories
done

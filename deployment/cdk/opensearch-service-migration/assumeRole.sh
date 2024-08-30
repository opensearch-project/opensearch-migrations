#!/bin/bash

# Check if the required argument is provided
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <role-arn> <role-session-name>"
    exit 1
fi

ROLE_ARN=$1
SESSION_NAME=$2

# Use AWS CLI to assume the role and capture the output
assume_role_output=$(aws sts assume-role --role-arn "$ROLE_ARN" --role-session-name "$SESSION_NAME")

# Check if the assume-role command was successful
if [ $? -ne 0 ]; then
    echo "Failed to assume role"
    exit 1
fi

# Extract the temporary credentials from the JSON output
export AWS_ACCESS_KEY_ID=$(echo $assume_role_output | jq -r '.Credentials.AccessKeyId')
export AWS_SECRET_ACCESS_KEY=$(echo $assume_role_output | jq -r '.Credentials.SecretAccessKey')
export AWS_SESSION_TOKEN=$(echo $assume_role_output | jq -r '.Credentials.SessionToken')

echo "AWS credentials have been set for the assumed role"


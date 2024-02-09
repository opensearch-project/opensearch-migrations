#!/bin/sh

PROFILE_NAME=${AWS_PROFILE:-"default"}
echo "Using profile " $PROFILE_NAME

CREDENTIALS_FILE="$HOME/.aws/credentials"

# Check if the AWS credentials file exists
if [ -f "$CREDENTIALS_FILE" ]; then
  if  grep -q "^\[$PROFILE_NAME\]" "$CREDENTIALS_FILE"; then
    export AWS_ACCESS_KEY_ID=$(awk -F "=" "/^\[$PROFILE_NAME\]/ {f=1} f==1 && /aws_access_key_id/ {print \$2; exit}" $CREDENTIALS_FILE)
    export AWS_SECRET_ACCESS_KEY=$(awk -F "=" "/^\[$PROFILE_NAME\]/ {f=1} f==1 && /aws_secret_access_key/ {print \$2; exit}" $CREDENTIALS_FILE)
    export AWS_SESSION_TOKEN=$(awk -F "=" "/^\[$PROFILE_NAME\]/ {f=1} f==1 && /aws_session_token/ {print \$2; exit}" $CREDENTIALS_FILE)
  fi
fi

"$@"

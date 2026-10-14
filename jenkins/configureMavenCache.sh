#!/usr/bin/env bash

# Only the endpoint and private file path may reach Jenkins/CPS stdout.
set +x
set -euo pipefail
umask 077

caller_arn="$(aws sts get-caller-identity --query Arn --output text)"
IFS=: read -r _ partition _ _ account _ <<< "$caller_arn"
reader_role="arn:${partition}:iam::${account}:role/${MIGRATIONS_CACHE_DOMAIN}-maven-reader"
session_credentials="$(aws sts assume-role \
    --role-arn "$reader_role" --role-session-name jenkins-maven-cache \
    --region "$MIGRATIONS_CACHE_REGION" \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text)"
read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<< "$session_credentials"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
unset session_credentials

endpoint="$(aws codeartifact get-repository-endpoint \
    --domain "$MIGRATIONS_CACHE_DOMAIN" --repository build-dependencies --format maven \
    --region "$MIGRATIONS_CACHE_REGION" --query repositoryEndpoint --output text)"

mkdir -p "$MIGRATIONS_CACHE_TMP_DIR"
private_dir="$(mktemp -d "${MIGRATIONS_CACHE_TMP_DIR}/maven-cache.XXXXXXXX")"
password_file="${private_dir}/password"
trap 'rm -f -- "$password_file"; rmdir -- "$private_dir"' EXIT
aws codeartifact get-authorization-token \
    --domain "$MIGRATIONS_CACHE_DOMAIN" --region "$MIGRATIONS_CACHE_REGION" \
    --duration-seconds 43200 --query authorizationToken --output text > "$password_file"
test -s "$password_file"
printf '%s\n%s\n' "$endpoint" "$password_file"
trap - EXIT

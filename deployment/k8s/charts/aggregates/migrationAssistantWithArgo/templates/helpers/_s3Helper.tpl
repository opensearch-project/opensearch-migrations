{{- define "migration.s3Functions" }}
  get_s3_endpoint_flag() {
    if [ -n "${S3_ENDPOINT_URL}" ]; then
      echo "--endpoint-url=$S3_ENDPOINT_URL"
    else
      echo ""
    fi
  }

  check_s3_available() {
    local bucket="$1"
    echo "Checking if S3 is available..."
    ENDPOINT_FLAG=$(get_s3_endpoint_flag)
    output=$(aws $ENDPOINT_FLAG s3api head-bucket --bucket "$bucket" 2>&1 >/dev/null)
    exit_code=$?
    if [ "$exit_code" -eq 0 ] || echo "$output" | grep -q 'Not Found\|NoSuchBucket'; then
      return 0
    fi
    echo "S3 check failed with error:"
    echo "$output"
    return 1
  }

  bucket_exists() {
    local bucket="$1"
    local max_retries="${2:-1}"
    local attempt=1

    ENDPOINT_FLAG=$(get_s3_endpoint_flag)
    while [ "$attempt" -le "$max_retries" ]; do
      output=$(aws $ENDPOINT_FLAG s3api head-bucket --bucket "$bucket" 2>&1 >/dev/null)
      exit_code=$?

      if [ "$exit_code" -eq 0 ]; then
        return 0
      fi
      if echo "$output" | grep -q 'Not Found\|NoSuchBucket'; then
        echo "Bucket '$bucket' does not exist."
        return 2
      fi
      if echo "$output" | grep -q 'AccessDenied'; then
        echo "Bucket exists but access denied."
        return 3
      fi

      echo "Attempt $attempt/$max_retries failed:"
      echo "$output"
      if [ "$attempt" -lt "$max_retries" ]; then
        sleep 2
      fi
      attempt=$((attempt + 1))
    done
    return 1
  }
{{- end }}

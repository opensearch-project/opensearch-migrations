{{- define "migration.s3Functions" }}
  get_s3_endpoint_flag() {
    if [ -n "${S3_ENDPOINT_URL}" ]; then
      echo "--endpoint-url=$S3_ENDPOINT_URL"
    else
      echo ""
    fi
  }

  check_s3_available() {
    echo "Checking if S3 is available..."
    ENDPOINT_FLAG=$(get_s3_endpoint_flag)
    if ! output=$(aws $ENDPOINT_FLAG s3 ls 2>&1 >/dev/null); then
      echo "S3 check failed with error:"
      echo "$output"
      return 1
    fi
    return 0
  }

  bucket_exists() {
    local bucket="$1"
    local max_retries="${2:-1}"
    local attempt=1

    ENDPOINT_FLAG=$(get_s3_endpoint_flag)
    while [ "$attempt" -le "$max_retries" ]; do
      if output=$(aws $ENDPOINT_FLAG s3api head-bucket --bucket "$bucket" 2>&1 >/dev/null); then
        return 0
      else
        echo "Attempt $attempt/$max_retries: Bucket not found or not accessible:"
        echo "$output"
        if [ "$attempt" -lt "$max_retries" ]; then
          sleep 2
        fi
        attempt=$((attempt + 1))
      fi
    done
    return 1
  }
{{- end }}

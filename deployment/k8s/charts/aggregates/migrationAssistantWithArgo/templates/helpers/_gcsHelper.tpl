{{- define "migration.gcsFunctions" }}
  check_gcs_available() {
    echo "Checking if GCS is available..."
    if ! output=$(gcloud storage buckets list --limit=1 2>&1); then
      echo "GCS check failed with error:"
      echo "$output"
      return 1
    fi
    return 0
  }

  bucket_exists() {
    local bucket="$1"
    local max_retries="${2:-1}"
    local attempt=1

    while [ "$attempt" -le "$max_retries" ]; do
      if gcloud storage buckets describe "gs://$bucket" > /dev/null 2>&1; then
        return 0
      fi
      echo "Attempt $attempt/$max_retries failed."
      if [ "$attempt" -lt "$max_retries" ]; then
        sleep 2
      fi
      attempt=$((attempt + 1))
    done
    return 1
  }
{{- end }}

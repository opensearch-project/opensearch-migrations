{{- define "generateRfsDeployment" -}}
apiVersion: apps/v1
kind: ReplicaSet
metadata:
  name: "bulk-loader-{{ "{{" }}inputs.parameters.unique-name{{ "}}" }}"
spec:
  replicas: {{ "{{" }}fromJson inputs.parameters.runtime-options.replicas{{ "}}" }}
  selector:
    matchLabels:
      app: "bulk-loader-{{ "{{" }}inputs.parameters.unique-name{{ "}}" }}"
  template:
    metadata:
      labels:
        app: "bulk-loader-{{ "{{" }}inputs.parameters.unique-name{{ "}}" }}"
        env: v1
    spec:
      containers:
        - name: bulk-load
          image: {{ "{{" }}fromJson inputs.parameters.runtime-options.image{{ "}}" }}
          imagePullPolicy: IfNotPresent
          env:
            - name: DOCUMENTS_PER_BULK_REQUEST
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["documents-per-bulk-request"]{{ "}}" }}
            - name: INITIAL_LEASE_DURATION
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["lease-duration"]{{ "}}" }}
            - name: LUCENE_DIR
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["lucene-dir"]{{ "}}" }}
            - name: SNAPSHOT_LOCAL_DIR
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["snapshot-local-dir"]{{ "}}" }}
            - name: SNAPSHOT_NAME
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["snapshot-name"]{{ "}}" }}
            - name: TARGET_HOST
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["target-host"]{{ "}}" }}
            - name: TARGET_INSECURE
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["target-insecure"]{{ "}}" }}
            - name: TARGET_PASSWORD
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["target-password"]{{ "}}" }}
            - name: TARGET_USERNAME
              value: {{ "{{" }}fromJson inputs.parameters.runtime-options["target-username"]{{ "}}" }}
          command:
            - "/bin/sh"
            - "-c"
            - |
                set -e
                ARGS=""
                ARGS="${ARGS}${DOCUMENTS_PER_BULK_REQUEST:+ --documentsPerBulkRequest $DOCUMENTS_PER_BULK_REQUEST}"
                ARGS="${ARGS}${INITIAL_LEASE_DURATION:+ --initialLeaseDuration $INITIAL_LEASE_DURATION}"
                ARGS="${ARGS}${LUCENE_DIR:+ --luceneDir $LUCENE_DIR}"
                ARGS="${ARGS}${SNAPSHOT_LOCAL_DIR:+ --snapshotLocalDir $SNAPSHOT_LOCAL_DIR}"
                ARGS="${ARGS}${SNAPSHOT_NAME:+ --snapshotName $SNAPSHOT_NAME}"
                ARGS="${ARGS}${TARGET_HOST:+ --targetHost $TARGET_HOST}"
                ARGS="${ARGS}${TARGET_PASSWORD:+ --targetPassword $TARGET_PASSWORD}"
                ARGS="${ARGS}${TARGET_USERNAME:+ --targetUsername $TARGET_USERNAME}"

                # Special handling for boolean flag
                if [ "$TARGET_INSECURE" = "true" ] || [ "$TARGET_INSECURE" = "1" ]; then
                    ARGS="${ARGS} --targetInsecure"
                fi

                export RFS_COMMAND="/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments $ARGS"
                exec /rfs-app/entrypoint.sh
          volumeMounts:
            - name: snapshot-volume
              mountPath: /snapshot
      volumes:
        - name: snapshot-volume
          persistentVolumeClaim:
            claimName: snapshot-volume-pvc

{{- end }}
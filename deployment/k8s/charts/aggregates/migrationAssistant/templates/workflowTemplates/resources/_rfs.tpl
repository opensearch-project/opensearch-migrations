{{- define "generateRfsDeployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: "{{ "{{" }}inputs.parameters.app-name{{ "}}" }}"
spec:
  replicas: {{ "{{" }}inputs.parameters.replicas{{ "}}" }}
  selector:
    matchLabels:
      app: "{{ "{{" }}inputs.parameters.app-name{{ "}}" }}"
  template:
    metadata:
      labels:
        app: "{{ "{{" }}inputs.parameters.app-name{{ "}}" }}"
        env: v1
    spec:
      containers:
        - name: bulk-load
          image: "{{ "{{" }}inputs.parameters.image{{ "}}" }}"
          imagePullPolicy: IfNotPresent
          env:
            - name: DOCUMENTS_PER_BULK_REQUEST
              value: "{{ "{{" }}inputs.parameters.documents-per-bulk-request{{ "}}" }}"
            - name: INITIAL_LEASE_DURATION
              value: "{{ "{{" }}inputs.parameters.lease-duration{{ "}}" }}"
            - name: LUCENE_DIR
              value: "{{ "{{" }}inputs.parameters.lucene-dir{{ "}}" }}"
            - name: SNAPSHOT_LOCAL_DIR
              value: "{{ "{{" }}inputs.parameters.snapshot-local-dir{{ "}}" }}"
            - name: SNAPSHOT_NAME
              value: "{{ "{{" }}inputs.parameters.snapshot-name{{ "}}" }}"
            - name: TARGET_HOST
              value: "{{ "{{" }}inputs.parameters.target-host{{ "}}" }}"
            - name: TARGET_INSECURE
              value: "{{ "{{" }}inputs.parameters.target-insecure{{ "}}" }}"
            - name: TARGET_PASSWORD
              value: "{{ "{{" }}inputs.parameters.target-password{{ "}}" }}"
            - name: TARGET_USERNAME
              value: "{{ "{{" }}inputs.parameters.target-username{{ "}}" }}"
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
            - name: env-vars
              mountPath: /shared
            - name: snapshot-volume
              mountPath: /snapshot
      volumes:
        - name: env-vars
          emptyDir: {}
        - name: snapshot-volume
          persistentVolumeClaim:
            claimName: snapshot-volume-pvc
{{- end }}
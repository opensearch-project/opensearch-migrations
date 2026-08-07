/**
 * Verify the CloudWatch dashboards managed by the ACK controller.
 *
 * Actions:
 *   create - wait for ACK reconciliation and verify both dashboard bodies exist.
 *   delete - delete both CRs, wait for finalizers, and verify both dashboards are gone.
 */
def call(Map config = [:]) {
    def action = config.action
    def kubeContext = config.kubeContext
    def stage = config.stage
    def region = config.region

    if (!(action in ['create', 'delete'])) {
        error("verifyEksDashboards: 'action' must be 'create' or 'delete'")
    }
    if (!kubeContext) { error("verifyEksDashboards: 'kubeContext' is required") }
    if (!stage) { error("verifyEksDashboards: 'stage' is required") }
    if (!region) { error("verifyEksDashboards: 'region' is required") }

    def dashboards = [
        [
            resource: "ma-${stage}-${region}-capturereplay",
            name: "MA-${stage}-${region}-CaptureReplay",
        ],
        [
            resource: "ma-${stage}-${region}-reindexfromsnapshot",
            name: "MA-${stage}-${region}-ReindexFromSnapshot",
        ],
    ]
    def resources = dashboards.collect {
        "'dashboards.cloudwatch.services.k8s.aws/${it.resource}'"
    }.join(' ')

    if (action == 'create') {
        sh """
            set -eu
            kubectl --context='${kubeContext}' --namespace ma wait \
              --for=condition=ACK.ResourceSynced=True \
              ${resources} \
              --timeout=180s
        """

        dashboards.each { dashboard ->
            sh """
                set -eu
                dashboard_body="\$(aws cloudwatch get-dashboard \
                  --dashboard-name '${dashboard.name}' \
                  --region '${region}' \
                  --query DashboardBody \
                  --output text)"
                if [ -z "\$dashboard_body" ] || [ "\$dashboard_body" = "None" ]; then
                    echo "CloudWatch dashboard '${dashboard.name}' has an empty body" >&2
                    exit 1
                fi
            """
        }
        return
    }

    sh """
        set -eu
        kubectl --context='${kubeContext}' --namespace ma delete \
          ${resources} \
          --wait=false
        kubectl --context='${kubeContext}' --namespace ma wait \
          --for=delete \
          ${resources} \
          --timeout=180s
    """

    dashboards.each { dashboard ->
        sh """
            set -eu
            attempts=0
            while [ "\$attempts" -lt 24 ]; do
                attempts=\$((attempts + 1))
                if output=\$(aws cloudwatch get-dashboard \
                    --dashboard-name '${dashboard.name}' \
                    --region '${region}' 2>&1); then
                    sleep 5
                    continue
                fi
                if echo "\$output" | grep -q ResourceNotFound; then
                    exit 0
                fi
                echo "Unexpected error checking CloudWatch dashboard '${dashboard.name}':" >&2
                echo "\$output" >&2
                exit 1
            done
            echo "CloudWatch dashboard '${dashboard.name}' still exists after 120 seconds" >&2
            exit 1
        """
    }
}

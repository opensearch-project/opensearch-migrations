import expr, {BaseExpression} from "@/schemas/expression";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {makeRequiredImageParametersForKeys} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/sharedTypes";


export const LocalstackHelper = WorkflowBuilder.create({
    k8sResourceName: "localstack-helper",
    serviceAccountName: "argo-workflow-executor"
})
    .addTemplate("resolveS3Endpoint", t=>t
        .addRequiredInput("s3Endpoint", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addArgs([getS3EndpointResolverContainer(b.inputs.s3Endpoint)])
        )
        .addPathOutput("resolvedS3Endpoint", "/tmp/resolved", typeToken<string>())
        .addPathOutput("isLocalstackEndpoint", "/tmp/isLocalstack", typeToken<boolean>())
    )
    .setEntrypoint("resolveS3Endpoint")
    .getFullScope();


export function getS3EndpointResolverContainer(s3Endpoint: BaseExpression<string>) {
    const template = `
set -euo pipefail

if [ -z "{{S3_ENDPOINT}}" ] || ! echo "{{S3_ENDPOINT}}" | grep -q "^http://localstack"; then
  echo "No S3 endpoint provided or not using localstack, skipping resolution"
  echo "{{S3_ENDPOINT}}" > /tmp/resolved
  echo "false" > /tmp/isLocalstack
  exit 0
fi

echo "Resolving S3 endpoint IP address for {{S3_ENDPOINT}}"
RAW="{{S3_ENDPOINT}}"
S3_HOST=$(echo "$RAW" | sed -E 's|^https?://||' | sed -E 's|/.*$||' | sed -E 's|:[0-9]+$||')
echo "Extracted hostname: $S3_HOST"

S3_IP=$(getent hosts "$S3_HOST" | awk '{ print $1 }')
if [ -z "$S3_IP" ]; then
  echo "Failed to resolve $S3_HOST, using original endpoint"
  FINAL_ENDPOINT="{{S3_ENDPOINT}}"
else
  echo "Resolved $S3_HOST to IP: $S3_IP"
  if echo "{{S3_ENDPOINT}}" | grep -q "^https"; then
    PROTOCOL="https://"
  else
    PROTOCOL="http://"
  fi

  PORT=$(echo "{{S3_ENDPOINT}}" | grep -Eo ':[0-9]+' || true)
  FINAL_ENDPOINT="\${PROTOCOL}\${S3_IP}\${PORT}"
fi

echo "Final resolved S3 endpoint: $FINAL_ENDPOINT"
echo "$FINAL_ENDPOINT" > /tmp/resolved
echo "true" > /tmp/isLocalstack
`;
    return expr.fillTemplate(template, {"S3_ENDPOINT": s3Endpoint});
}

{{/*
Annotations that make the EKS Auto Mode load balancing controller stamp `aws.resourceTags` onto
the load balancer, target groups, listeners and load balancer security group it creates.

Unlike nodes and volumes, there is no cluster-wide place to declare load balancer tags: the Auto
Mode controller is managed by EKS, so its `--default-tags` flag is not reachable. For NLBs
(`Service` of type `LoadBalancer`) the annotation is the only option and has to be repeated on
every object. For ALBs an `IngressClassParams` with `spec.tags` covers every `Ingress` using that
`IngressClass`, so prefer that if this chart ever grows one. Include this in the metadata of any
such object so it stays in step with the rest of the deployment:

    metadata:
      annotations:
        {{- include "migration.awsLoadBalancerTagAnnotations" . | nindent 4 }}

Renders nothing when no tags are configured, so it is safe to include unconditionally.
*/}}
{{- define "migration.awsLoadBalancerTagAnnotations" -}}
{{- $tags := .Values.aws.resourceTags | default dict -}}
{{- if $tags -}}
{{- $pairs := list -}}
{{- range $key, $value := $tags -}}
{{- $pairs = append $pairs (printf "%s=%s" $key (toString $value)) -}}
{{- end }}
service.beta.kubernetes.io/aws-load-balancer-additional-resource-tags: {{ join "," $pairs | quote }}
alb.ingress.kubernetes.io/tags: {{ join "," $pairs | quote }}
{{- end -}}
{{- end -}}

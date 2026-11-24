# Derive a tier-n manifest (Deployment or PodDisruptionBudget) from the LIVE tier-0 object, so
# the manifests the monitor later applies can never drift from the shape that actually runs.
#
# Args:
#   $tier       tier number, as a string (label values and env values are strings)
#   $memory     the tier's memory quantity (requests.memory == limits.memory)
#   $tierLabel  the rfs-memory-tier label key
#   $name       the tier object's name (<session>-rfs-t<n>)
#   $container  the bulk-loader container name
#
# No quantity arithmetic happens here: $memory comes from the config processor's precomputed
# tier table. Deployments are stored with replicas 0; the monitor sets replicas when it
# creates/reactivates a tier.

def scrub:
  del(.metadata.uid, .metadata.resourceVersion, .metadata.generation,
      .metadata.creationTimestamp, .metadata.managedFields, .metadata.selfLink, .status)
  | .metadata.annotations |= (if . != null then
        del(."deployment.kubernetes.io/revision",
            ."kubectl.kubernetes.io/last-applied-configuration")
      else . end)
  | (if .metadata.annotations == {} or .metadata.annotations == null
       then del(.metadata.annotations) else . end);

scrub
| .metadata.name = $name
| .metadata.labels[$tierLabel] = $tier
| .spec.selector.matchLabels[$tierLabel] = $tier
| (if .kind == "Deployment" then
     .spec.template.metadata.labels[$tierLabel] = $tier
     | .spec.replicas = 0
     | .spec.template.spec.containers |= map(
         if .name == $container then
           .resources.requests.memory = $memory
           | .resources.limits.memory = $memory
           | .env |= map(if .name == "RFS_WORKER_MEMORY_TIER" then .value = $tier else . end)
         else . end)
   else . end)

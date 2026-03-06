package org.opensearch.migrations.image

/**
 * Rewrites image references to use ECR pull-through cache URIs.
 *
 * Given an ECR endpoint like "123456789.dkr.ecr.us-east-1.amazonaws.com",
 * maps upstream registries to their pull-through cache prefixes:
 *   docker.io/library/nginx:latest → 123456789.dkr.ecr.us-east-1.amazonaws.com/docker-hub/library/nginx:latest
 *   quay.io/prometheus/prometheus  → 123456789.dkr.ecr.us-east-1.amazonaws.com/quay/prometheus/prometheus
 */
class PullThroughCacheHelper {

    // Upstream registry hostname → ECR pull-through prefix
    private static final Map<String, String> REGISTRY_PREFIX_MAP = [
        'docker.io'             : 'docker-hub',
        'registry-1.docker.io'  : 'docker-hub',
        'public.ecr.aws'        : 'ecr-public',
        'ghcr.io'               : 'github-container-registry',
        'registry.gitlab.com'   : 'gitlab-container-registry',
        'registry.k8s.io'       : 'k8s',
        'quay.io'               : 'quay',
    ]

    private final String ecrEndpoint

    PullThroughCacheHelper(String ecrEndpoint) {
        this.ecrEndpoint = ecrEndpoint
    }

    /**
     * Rewrite a full image reference through the pull-through cache.
     * Returns the original unchanged if the registry isn't mapped or ecrEndpoint is empty.
     *
     * Examples:
     *   rewrite("docker.io/library/amazoncorretto:17") → "<ecr>/docker-hub/library/amazoncorretto:17"
     *   rewrite("public.ecr.aws/aws-observability/collector:v1") → "<ecr>/ecr-public/aws-observability/collector:v1"
     *   rewrite("quay.io/prom/prometheus:latest") → "<ecr>/quay/prom/prometheus:latest"
     *   rewrite("nginx:latest") → "<ecr>/docker-hub/library/nginx:latest"  (implicit docker hub)
     *   rewrite("docker.elastic.co/elasticsearch:7.10") → unchanged (no mapping)
     */
    String rewrite(String imageRef) {
        if (!ecrEndpoint || !imageRef) return imageRef

        def parts = parseImageRef(imageRef)
        def prefix = REGISTRY_PREFIX_MAP[parts.registry]
        if (prefix == null) return imageRef

        return "${ecrEndpoint}/${prefix}/${parts.path}"
    }

    /**
     * Build a base image config map for Gradle image build tasks, rewriting through
     * pull-through cache when the endpoint is configured.
     *
     * Returns a map with keys: baseImageRegistryEndpoint, baseImageGroup, baseImageName, baseImageTag,
     * and optionally baseImageOverride if the image was rewritten through the cache.
     */
    Map baseImageConfig(String endpoint, String group, String name, String tag) {
        def base = [baseImageRegistryEndpoint: endpoint, baseImageGroup: group, baseImageName: name, baseImageTag: tag]
        def fullRef = group ? "${endpoint}/${group}/${name}:${tag}" : "${endpoint}/${name}:${tag}"
        def rewritten = rewrite(fullRef)
        if (rewritten != fullRef) {
            base.baseImageOverride = rewritten
        }
        return base
    }

    /**
     * Parses an image reference into its registry and path components.
     *
     * Expected behavior:
     *   "nginx:latest"                          → registry="docker.io", path="library/nginx:latest"  (implicit Docker Hub + library prefix)
     *   "grafana/grafana:11.6.1"                → registry="docker.io", path="grafana/grafana:11.6.1" (no dot in first segment → Docker Hub)
     *   "docker.io/library/amazoncorretto:17"   → registry="docker.io", path="library/amazoncorretto:17"
     *   "public.ecr.aws/aws-observability/col"  → registry="public.ecr.aws", path="aws-observability/col"
     *   "quay.io/prom/prometheus:latest"        → registry="quay.io", path="prom/prometheus:latest"
     *   "nginx"                                 → registry="docker.io", path="library/nginx" (single segment, no tag)
     *   "myimage@sha256:abc..."                 → registry="docker.io", path="library/myimage@sha256:abc..."
     */
    private static Map parseImageRef(String imageRef) {
        def refWithoutTag = imageRef
        def tagPart = ''

        if (imageRef.contains('@')) {
            def idx = imageRef.indexOf('@')
            refWithoutTag = imageRef.substring(0, idx)
            tagPart = imageRef.substring(idx)
        } else if (imageRef.contains(':') && !imageRef.substring(0, imageRef.indexOf(':')).contains('/')) {
            // Simple case like "nginx:latest" — no registry, implicit docker hub
            return [registry: 'docker.io', path: "library/${imageRef}"]
        } else if (imageRef.contains(':')) {
            def lastColon = imageRef.lastIndexOf(':')
            def beforeColon = imageRef.substring(0, lastColon)
            if (!beforeColon.substring(beforeColon.lastIndexOf('/') + 1).contains('.')) {
                refWithoutTag = beforeColon
                tagPart = ":${imageRef.substring(lastColon + 1)}"
            }
        }

        def segments = refWithoutTag.split('/')
        if (segments.length == 1) {
            return [registry: 'docker.io', path: "library/${imageRef}"]
        }

        def firstSegment = segments[0]
        if (firstSegment.contains('.') || firstSegment.contains(':')) {
            def path = segments[1..-1].join('/') + tagPart
            return [registry: firstSegment, path: path]
        }

        // No explicit registry → Docker Hub (e.g., "grafana/grafana:latest")
        return [registry: 'docker.io', path: "${refWithoutTag}${tagPart}"]
    }
}

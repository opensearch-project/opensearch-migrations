package org.opensearch.migrations.image

interface ImageRegistryFormatter {
    String getFullBaseImageIdentifier(String baseImageRegistryEndpoint, String baseImageGroup, String baseImageName, String baseImageTag)
    List<String> getFullTargetImageIdentifier(String registryEndpoint, String imageName, String imageTag)
}

class DefaultRegistryFormatter implements ImageRegistryFormatter {
    @Override
    String getFullBaseImageIdentifier(String endpoint, String group, String name, String tag) {
        def baseImage = "${name}:${tag}"
        if (group) {
            baseImage = "${group}/${baseImage}"
        }
        if (endpoint) {
            baseImage = "${endpoint}/${baseImage}"
        }
        return baseImage
    }

    @Override
    List<String> getFullTargetImageIdentifier(String endpoint, String name, String tag) {
        def registryDestination = "${endpoint}/migrations/${name}:${tag}"
        def cacheDestination = "${endpoint}/migrations/${name}:cache"
        return [registryDestination, cacheDestination]
    }
}

class ECRRegistryFormatter implements ImageRegistryFormatter {
    @Override
    String getFullBaseImageIdentifier(String endpoint, String group, String name, String tag) {
        def baseImage = "${name}_${tag}"
        if (group) {
            baseImage = "${group}_${baseImage}"
        }
        if (endpoint) {
            baseImage = "${endpoint}:${baseImage}"
        }
        return baseImage
    }

    @Override
    List<String> getFullTargetImageIdentifier(String endpoint, String name, String tag) {
        def registryDestination = "${endpoint}:migrations_${name}_${tag}"
        def cacheDestination = "${endpoint}:migrations_${name}_cache"
        return [registryDestination, cacheDestination]
    }
}

class ImageRegistryFormatterFactory {
    static ImageRegistryFormatter getFormatter(String endpoint) {
        if (endpoint?.contains(".ecr.") && endpoint.contains(".amazonaws.com")) {
            return new ECRRegistryFormatter()
        }
        return new DefaultRegistryFormatter()
    }
}

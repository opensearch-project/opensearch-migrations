## Transformations

The behavior of Elasticsearch and OpenSearch has evolved over time. These differences sometimes impact both low-level data and high-level functionality. Supporting the transformation between implementations is key to successfully migrating between products.

There are network-level transformations for remapping network requests to different clusters or even protocols, and document metadata transformations such as moving between Lucene versions or index features.

This document and the solutions within will provide details on what kinds of transformations are available, how to validate them, how to use them, and how to add additional transformations for new behavioral differences discovered over time.

### Network Transformation
When requests are redirected through traffic capture and traffic replay systems, these requests need to be adjusted based on network topology and application configuration.

These transformations could involve stripping headers from the HTTP request, rewriting URLs, or altering the content encoding headers and body.

Since these configurations are very specific, the declaration is done via JSON format through either JMESPathTransformer and JOLT-based systems. See [Traffic Replayer](../TrafficCapture/trafficReplayer/README.md) for specific details on how network transformations have been built out for these scenarios.

### Data Transformation
Persisting document data through a migration is often seen as the majority of the work. Additionally, these documents are used within index configuration and cluster settings that need to be configured for the target of the migration.

Transformations may be required to move away from deprecated configurations or to adopt features that better handle the workflows of a cluster.

The version of the source and target clusters is a key requirement to determine what transformations are conducted and might require user input, such as choosing to drop fields of unsupported data types or applying a conversion process.

We have little prior experience around how these transformations are declared, organized, and tested, leading us to consider the following proposals.

## Proposal 0 - No changes
There are versions of data transformation in the RFS codebase [1] and in Traffic Capture/Replay [2]. As disjointed systems, they can continue to iterate independently of one another without conflicts or redesigns.

### Details
For the RFS solution, transformers are version-specific, such as ES 6.8 -> OS 2.11. Entities are passed as raw JSON into the version-specific transformation function, see the interface below.

```java
public interface Transformer {
    public ObjectNode transformGlobalMetadata(ObjectNode root);
    public ObjectNode transformIndexMetadata(ObjectNode root);    
}
```

Within Traffic Capture/Replay, the request abstraction is at the network request layer with the entity being encoded as part of the incoming JSON. It only supports document-level transformation; other metadata transformation is done externally.

```java
public interface IJsonTransformer {
    Map<String,Object> transformJson(Map<String,Object> incomingJson);
}
```

- [1] [ES_6_8_to_OS_2_11](../RFS/src/main/java/com/rfs/transformers/Transformer_ES_6_8_to_OS_2_11.java)
- [2] [openSearch23PlusTargetTransformerProvider](../TrafficCapture/transformationPlugins/jsonMessageTransformers/openSearch23PlusTargetTransformerProvider/src/main/java/org/opensearch/migrations/transform/JsonTypeMappingTransformer.java)

#### Pros
- Fastest to deliver
- No refactoring or redesign needed to iterate on RFS ecosystem

#### Cons
- Traffic Capture/Replay is missing metadata migration, needs to be built
- Supporting new migrations requires duplicating both variants

## [Recommendation] Proposal 1 - Data Transformation Rules

Example rule for handling the nested objects limit that was added in OpenSearch 1.0 that would prevent a cluster from starting if the limit was too high.  Additional the limit could be lowered manually which would cause the same problem and errors.

```java
// Handle https://opensearch.org/docs/latest/breaking-changes/#migrating-to-opensearch-and-limits-on-the-number-of-nested-json-objects
public class NestedObjectsLimitRule implements TransformationRule<Index> {
    @Override
    public Version minSupportedSourceVersion() {
        return new Version("ES", 6, 8, 0);
    }

    @Override
    public Version minRequiredTargetVersion() {
        return new Version("OS", 1, 0, 0);
    }

    boolean canApply(Index index) {
        var nesting_limit = index.getImmutableCluster()
            .getClusterSetting("index.mapping.nested_objects.limit")
            .asLong()
            .getOrDefault(1000L);
        return index.getImmutableCluster()
            .getImmutableIndices()
            .stream()
            .anyOf(index -> calcNestedObjectsMappings(index.getMappings()) >= nesting_limit);
    }

    boolean applyTransformation(Index entity) {
        deleteNestedObjectsMappingsAfter(1000, index.getMappings());
    }
}
```
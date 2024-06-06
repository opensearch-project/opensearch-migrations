## [Request for comments] Transformations

Behavior of Elasticsearch and OpenSearch has evolved over time.  These differences sometimes impact low level data and high level functionality.  Supporting the transformation between implementations is key to successfully migrating between products.

There are network level transformations to remapping network requests to different clusters or even protocals and document metadata transformations such as moving between lucene versions or index features. 

This document and the solutions underneath will provide details on what kinds of transformations are avaliable, how to validate them, how to use them, and how to add additional transformations for new behavioral differences found over time.

### Network Transformation
When requests are being redirected through traffic capture and traffic replay systems, these requests will need to be adjusted based on network topology and application configuration.

These transformations could be stripping header from the http request, rewriting url, or alterations to the content encoding headers and body.

Since these configurations are very specific the declaration is done via json format through either JMESPathTransformer and JOLT based systems.  See [Traffic Replayer](..TrafficCapture/trafficReplayer/README.md) for specific details on how network transformation have been built out for these scenarios. 

### Data Transformation
Persisting documents data through a migration is often seen as the majority of work. In additional, these documents are used within index configuration and cluster settings that need to be configured for the target of the migration.

Transformations can be required to moving from away deprecating configurations or moving towards features that better handle the workflows of a cluster.

The version of the source and target clusters is a key requirement to determine what transformations are conducted and might require user input - such as choosing to drop fields of unsupported data types or applying a conversion process.

We have little prior art are around how these transformations are declared, organized, and tested.

## Proposal 0 - No changes
There are versions of data transformation in the RFS codebase [1] and in Traffic Capture/Replay [2].  As disjoined systsems they can continue to iterate independant of one another without conflicts or redesigns.

### Details
For the RFS solution transformers are version to version specific, such as ES 6.8 -> OS 2.11.  Entities is passed as raw json into the versino specific transformation function, see the interface below. 

```java
public interface Transformer {
    public ObjectNode transformGlobalMetadata(ObjectNode root);
    public ObjectNode transformIndexMetadata(ObjectNode root);    
}
```

Within Traffic Capture/Replay the request abstraction is at the network request layer with the entity being encoded as part of the incoming json.  It only support document level transformation, other metadata transformation is done externally.


```java
public interface IJsonTransformer {
    Map<String,Object> transformJson(Map<String,Object> incomingJson);
}
```


- [1]  [ES_6_8_to_OS_2_11](../RFS/src/main/java/com/rfs/transformers/Transformer_ES_6_8_to_OS_2_11.java)
- [2]  [openSearch23PlusTargetTransformerProvider](../TrafficCapture/transformationPlugins/jsonMessageTransformers/openSearch23PlusTargetTransformerProvider/src/main/java/org/opensearch/migrations/transform/JsonTypeMappingTransformer.java)

#### Pros
- Fastest to deliver
- No refactoring or redesign is need to iterate on RFS ecosystem
#### Cons
- Traffic Capture/Replay is missing metadata migration, needs to be built
- Supporting new migrations need to be duplicate both variants

## [Recommendation] Proposal 1 - Data Transformation Rules

```java

interface ClusterTransformationRule {
    Version minSupportedSourceVersion();
    Version minRequiredTargetVersion();
    boolean canApply(Cluster|Index|Document);
    boolean applyTransformation(Document);
}
```


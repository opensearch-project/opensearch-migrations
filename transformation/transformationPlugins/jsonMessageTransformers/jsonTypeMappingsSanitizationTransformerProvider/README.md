# Configuration Routing

See the [README for the Type Mappings Sanitization Transformer](../jsonTypeMappingsSanitizationTransformer/README.md) 
for specifics of how rules are evaluated.

This package loads a [Type Mappings Sanitization Transformer](../jsonTypeMappingsSanitizationTransformer/src/main/java/org/opensearch/migrations/transform/TypeMappingsSanitizationTransformer.java)
for that appropriate application variant (currently only for the REPLAYER) along with the configuration passed into
the provider.  This [Provider](./src/main/java/org/opensearch/migrations/transform/TypeMappingSanitizationTransformerProvider.java)
pulls the values for keys `featureFlags`, `staticMappings`, and `regexIndexMappings` from the incoming configuration map
object so that the Type Mappings Sanitization Transformer can adjust requests for specific type mappings into the 
appropriate target index.  

The following example will load a Transformer to rewrite types as per the static mappings shown in the second key-value
(staticMappings), or if not present, will then default to the mappings in regexIndexMappings.  Note that regexIndexMappings will
only be checked if there are no entries in staticMappings.  
staticMappings index names (top-level key) and keys to their children maps will be evaluated literally, not as patterns.  
Patterns are ONLY supported via regexIndexMappings.

```
{
    "staticMappings": {
        "indexA": {
            "type2": "indexA_2",
            "type1": "indexA_1"
        },
        "indexB": {
            "type2": "indexB",
            "type1": "indexB"
        },
        "indexC": {
            "type2": "indexC"
        }
    },
    "regexIndexMappings": [
        [ "(time*)", "(type*)", "\\1_And_\\2" ]
    ]
}
```
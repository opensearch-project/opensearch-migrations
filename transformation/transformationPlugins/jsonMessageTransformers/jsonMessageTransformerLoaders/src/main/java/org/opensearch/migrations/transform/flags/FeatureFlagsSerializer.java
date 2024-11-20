//package org.opensearch.migrations.transform.flags;
//
//import java.io.IOException;
//import java.util.Map;
//
//import com.fasterxml.jackson.core.JsonGenerator;
//import com.fasterxml.jackson.databind.SerializerProvider;
//import com.fasterxml.jackson.databind.ser.std.StdSerializer;
//
//public class FeatureFlagsSerializer extends StdSerializer<FeatureFlags> {
//
//    public FeatureFlagsSerializer() {
//        this(null);
//    }
//
//    public FeatureFlagsSerializer(Class<FeatureFlags> t) {
//        super(t);
//    }
//
//    @Override
//    public void serialize(FeatureFlags value, JsonGenerator gen, SerializerProvider provider) throws IOException {
//        gen.writeStartObject();
//        // Serialize the 'enabled' field
//        gen.writeBooleanField("enabled", value.isEnabled());
//
//        // Serialize all map entries
//        for (Map.Entry<String, FeatureFlags> entry : value.entrySet()) {
//            gen.writeObjectField(entry.getKey(), entry.getValue());
//        }
//
//        gen.writeEndObject();
//    }
//}

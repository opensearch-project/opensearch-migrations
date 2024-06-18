package com.rfs.transformation.es_6_8;

import java.util.Map;

public class SingleTypeRemovalTest {


    public String wrapMappings(final String s) {
        return "{\"mappings\": {" + s + "}}";
    }

    /*
     * 
     * On index properties;
     * { mappings: {
     *     "type1": {...}
     * }
     *
     * On index-type1 properties;
     * { mappings: {...}
     * }
     */
    
    /*
     * On index properties;
     * { mappings: {
     *     "type1": {...}
     *     "type2": {...}
     *   }
     * }
     * 
     * On index-type1 properties;
     * { mappings: {...}
     * }
     * On index-type2 properties;
     * { mappings: {...}
     * }
     */
    public void test1() {
        wrapMappings("\"type1\":{}");
        var indexSetting = Map.of("mappings", Map.of("type1", Map.of(), "type2", Map.of()));

        // throws not supported exception
    }

    public void test2() {
        var indexSetting = Map.of("mappings", Map.of("type1", Map.of()));

    }

    public void test3() {
        var indexSetting = Map.of("mappings", Map.of("_doc", Map.of()));

    }


}

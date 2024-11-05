package org.opensearch.migrations.data;

public enum ElasticsearchType {
    DATE("date"),
    GEO_POINT("geo_point"),
    INTEGER("integer"),
    KEYWORD("keyword"),
    LONG("long"),
    TEXT("text"),
    SCALED_FLOAT("scaled_float"),
    IP("ip"),
    NESTED("nested");

    private final String value;

    ElasticsearchType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

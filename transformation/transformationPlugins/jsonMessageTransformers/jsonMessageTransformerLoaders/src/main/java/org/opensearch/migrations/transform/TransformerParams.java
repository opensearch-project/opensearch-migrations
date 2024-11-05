package org.opensearch.migrations.transform;

public interface TransformerParams {
    String getTransformerConfigParameterArgPrefix();

    String getTransformerConfigEncoded();

    String getTransformerConfig();

    String getTransformerConfigFile();
}

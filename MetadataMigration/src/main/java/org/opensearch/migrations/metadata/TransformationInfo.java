package org.opensearch.migrations.metadata;

import org.opensearch.migrations.transformation.CanApplyResult;

public class TransformationInfo {

    public String name;
    public CanApplyResult status;
    public String details;

}

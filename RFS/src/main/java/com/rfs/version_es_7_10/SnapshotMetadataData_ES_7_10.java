package com.rfs.version_es_7_10;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;


public class SnapshotMetadataData_ES_7_10 implements com.rfs.common.SnapshotMetadata.Data{

    private String name;
    private String uuid;
    @JsonProperty("version_id")
    private int versionId;
    private List<String> indices;
    private String state;
    private String reason;
    @JsonProperty("include_global_state")
    private boolean includeGlobalState;
    @JsonProperty("start_time")
    private long startTime;
    @JsonProperty("end_time")
    private long endTime;
    @JsonProperty("total_shards")
    private int totalShards;
    @JsonProperty("successful_shards")
    private int successfulShards;
    private List<?> failures; // Haven't looked into this yet
    @JsonProperty("data_streams")
    private List<?> dataStreams; // Haven't looked into this yet
    @JsonProperty("metadata")
    private Object metaData; // Haven't looked into this yet

    public String getName() {
        return name;
    }

    public String getUuid() {
        return uuid;
    }

    public int getVersionId() {
        return versionId;
    }

    public List<String> getIndices() {
        return indices;
    }

    public String getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public boolean isIncludeGlobalState() {
        return includeGlobalState;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public int getTotalShards() {
        return totalShards;
    }

    public int getSuccessfulShards() {
        return successfulShards;
    }

    public List<?> getFailures() {
        return failures;
    }
}

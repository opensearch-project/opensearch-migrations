package com.rfs.version_es_6_8;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.rfs.models.SnapshotMetadata;

import lombok.Getter;

@Getter
public class SnapshotMetadataData_ES_6_8 implements SnapshotMetadata {
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
    private List<?> failures; // Haven't looked at this yet
}

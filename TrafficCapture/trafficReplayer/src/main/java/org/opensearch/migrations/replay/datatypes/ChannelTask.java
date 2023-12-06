package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class ChannelTask {
    public final ChannelTaskType kind;
    public final Runnable runnable;
}

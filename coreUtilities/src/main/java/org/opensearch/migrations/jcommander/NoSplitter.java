package org.opensearch.migrations.jcommander;

import java.util.List;

import com.beust.jcommander.converters.IParameterSplitter;

public class NoSplitter implements IParameterSplitter {
    @Override
    public List<String> split(String value) {
        return List.of(value);
    }
}

package com.rfs.common;

import java.text.ParseException;

import org.opensearch.migrations.Version;

import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.BaseConverter;

public class VersionConverter extends BaseConverter<Version> {

    public VersionConverter(final String optionName) {
        super(optionName);
    }

    @Override
    public Version convert(final String value) {
        try {
            return Version.fromString(value);
        } catch (ParseException pe) {
            throw new ParameterException(getErrorString(value, "Version object"));
        }
    }
    
}

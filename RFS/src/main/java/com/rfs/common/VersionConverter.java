package com.rfs.common;

import org.apache.logging.log4j.core.parser.ParseException;

import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.BaseConverter;

import src.org.opensearch.migrations.Version;

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

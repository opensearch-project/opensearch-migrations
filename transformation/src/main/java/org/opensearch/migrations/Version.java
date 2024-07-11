package org.opensearch.migrations;

import java.text.ParseException;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@RequiredArgsConstructor
@Getter
@Builder
@EqualsAndHashCode
@ToString
public class Version {
    private final Flavor flavor;
    private final int major;
    private final int minor;
    private final int patch;

    public static Version fromString(final String raw) throws ParseException {
        var builder = Version.builder();
        var remainingString = raw.toLowerCase();

        for(var flavor : Flavor.values()) {
            if (remainingString.startsWith(flavor.name().toLowerCase())) {
                remainingString = remainingString.substring(flavor.name().length());
                builder.flavor(flavor);
                break;
            } else if (remainingString.startsWith(flavor.shorthand.toLowerCase())) {
                remainingString = remainingString.substring(flavor.shorthand.length());
                builder.flavor(flavor);
                break;
            }
        }

        if (remainingString.equals(raw.toLowerCase())) {
            throw new ParseException("Unable to determine build flavor from '" + raw +"'", 0);
        }

        try {
            // Remove any spaces
            remainingString = remainingString.split("[ _]+", 2)[1];

            // Break out into the numeric parts
            var versionParts = remainingString.split("[\\._]");

            builder.major(Integer.parseInt(versionParts[0]));

            if (versionParts.length > 1) {
                builder.minor(versionParts[1].equals("x") ? 0 : Integer.parseInt(versionParts[1]));
            }

            if (versionParts.length > 2) {
                builder.patch(versionParts[2].equals("x") ? 0 : Integer.parseInt(versionParts[2]));
            }
            return builder.build();
        } catch (Exception e) {
            throw new ParseException("Unable to parse version numbers from the string '" + raw + "': " + e.getMessage(), 0);
        }
    }
}

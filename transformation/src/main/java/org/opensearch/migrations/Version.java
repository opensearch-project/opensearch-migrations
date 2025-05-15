package org.opensearch.migrations;

import java.util.Arrays;
import java.util.Comparator;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
@Builder
@EqualsAndHashCode
public class Version {
    @NonNull
    private final Flavor flavor;
    private final int major;
    private final int minor;
    private final int patch;

    public String toString() {
        return String.format("%s %d.%d.%d", flavor.name(), major, minor, patch);
    }

    public static Version fromString(final String raw) throws RuntimeException {
        var builder = Version.builder();
        var remainingString = raw.toLowerCase();

        var finalRemainingString = remainingString;
        var matchedFlavor = Arrays.stream(Flavor.values())
            .sorted(Comparator.comparing((Flavor f) -> f.shorthand.length()).reversed())
            .filter(flavor -> finalRemainingString.startsWith(flavor.name().toLowerCase()) ||
                              finalRemainingString.startsWith(flavor.shorthand.toLowerCase()))
            .findFirst();

        if (matchedFlavor.isPresent()) {
            Flavor flavor = matchedFlavor.get();
            remainingString = remainingString.startsWith(flavor.name().toLowerCase()) ?
                remainingString.substring(flavor.name().length()) :
                remainingString.substring(flavor.shorthand.length());
            builder.flavor(flavor);
        } else {
            throw new IllegalArgumentException("Unable to determine build flavor from '" + raw +"'");
        }

        try {
            // Remove any spaces
            remainingString = remainingString.trim();
            // Remove any _ separators from the digits
            remainingString = remainingString.replaceFirst("^[_v]+", "");

            // Break out into the numeric parts
            var versionParts = remainingString.split("[\\._-]");

            builder.major(Integer.parseInt(versionParts[0]));

            if (versionParts.length > 1) {
                builder.minor(versionParts[1].equals("x") ? 0 : Integer.parseInt(versionParts[1]));
            }

            if (versionParts.length > 2) {
                builder.patch(versionParts[2].equals("x") ? 0 : Integer.parseInt(versionParts[2]));
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse version numbers from the string '" + raw + "'\r\n", e);
        }
    }
}

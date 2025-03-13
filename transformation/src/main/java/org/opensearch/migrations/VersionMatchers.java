package org.opensearch.migrations;

import java.util.function.Predicate;

import lombok.experimental.UtilityClass;

@UtilityClass
public class VersionMatchers {
    public static final Predicate<Version> isES_2_X = VersionMatchers.matchesMajorVersion(Version.fromString("ES 2.4"));
    public static final Predicate<Version> isES_5_X = VersionMatchers.matchesMajorVersion(Version.fromString("ES 5.6"));
    public static final Predicate<Version> isES_6_X = VersionMatchers.matchesMajorVersion(Version.fromString("ES 6.8"));
    public static final Predicate<Version> isES_7_X = VersionMatchers.matchesMajorVersion(Version.fromString("ES 7.10"));
    public static final Predicate<Version> isES_7_10 = VersionMatchers.matchesMinorVersion(Version.fromString("ES 7.10.2"));
    public static final Predicate<Version> equalOrGreaterThanES_7_10 = VersionMatchers.equalOrGreaterThanMinorVersion(Version.fromString("ES 7.10"));

    public static final Predicate<Version> isOS_1_X = VersionMatchers.matchesMajorVersion(Version.fromString("OS 1.0.0"));
    public static final Predicate<Version> isOS_2_X = VersionMatchers.matchesMajorVersion(Version.fromString("OS 2.0.0"));
    public static final Predicate<Version> isOS_2_19 = VersionMatchers.matchesMinorVersion(Version.fromString("OS 2.19.1"));

    private static Predicate<Flavor> compatibleFlavor(final Flavor flavor) {
        return other -> {
            if (other == null) {
                return false;
            }
            if (other.isOpenSearch() && flavor.isOpenSearch()) {
                return true;
            }
            return other == flavor;
        };
    }

    private static Predicate<Version> matchesMajorVersion(final Version version) {
        return other -> {
            if (other == null) {
                return false;
            }
            var flavorMatches = compatibleFlavor(other.getFlavor()).test(version.getFlavor());
            var majorVersionNumberMatches = version.getMajor() == other.getMajor();

            return flavorMatches && majorVersionNumberMatches;
        };
    }

    private static Predicate<Version> matchesMinorVersion(final Version version) {
        return other -> matchesMajorVersion(version)
            .and(other2 -> version.getMinor() == other2.getMinor())
            .test(other);
    }

    private static Predicate<Version> equalOrGreaterThanMinorVersion(final Version version) {
        return other -> matchesMajorVersion(version)
            .and(other2 -> version.getMinor() <= other2.getMinor())
            .test(other);
    }
}

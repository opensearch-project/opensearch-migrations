package org.opensearch.migrations;

import java.util.function.Predicate;

import lombok.experimental.UtilityClass;

/** Only use after loose version checking is enabled by a user */
@UtilityClass
public class UnboundVersionMatchers {

    public static final Predicate<Version> anyES = VersionMatchers.matchesFlavor(Version.fromString("ES 1.0.0"));
    public static final Predicate<Version> isBelowES_6_X = belowMajorVersion(Version.fromString("ES 6.0.0"));
    public static final Predicate<Version> isBelowES_7_X = belowMajorVersion(Version.fromString("ES 7.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_6_X = greaterOrEqualMajorVersion(Version.fromString("ES 6.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_7_X = greaterOrEqualMajorVersion(Version.fromString("ES 7.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_7_10 = greaterOrEqualMajorVersion(Version.fromString("ES 7.10.0"));
    public static final Predicate<Version> anyOS = VersionMatchers.matchesFlavor(Version.fromString("OS 1.0.0"));

    static Predicate<Version> belowMajorVersion(final Version version) {
        return other -> {
            if (other == null) {
                return false;
            }
            var flavorMatches = VersionMatchers.matchesFlavor(other).test(version);
            var isLowerMajorVersion = other.getMajor() < version.getMajor();
            return flavorMatches && isLowerMajorVersion;
        };
    }

    static Predicate<Version> greaterOrEqualMajorVersion(final Version version) {
        return other -> {
            if (other == null) {
                return false;
            }
            var flavorMatches = VersionMatchers.matchesFlavor(other).test(version);
            var isLowerMajorVersion = other.getMajor() >= version.getMajor();
            return flavorMatches && isLowerMajorVersion;
        };
    }
}

package org.opensearch.migrations;

import java.util.function.Predicate;

import lombok.experimental.UtilityClass;

/** Only use after loose version checking is enabled by a user */
@UtilityClass
public class UnboundVersionMatchers {

    public static final Predicate<Version> anyES = VersionMatchers.matchesFlavor(Version.fromString("ES 1.0.0"));
    public static final Predicate<Version> isBelowES_2_X = belowMajorVersion(Version.fromString("ES 2.0.0"));
    public static final Predicate<Version> isBelowES_5_X = belowMajorVersion(Version.fromString("ES 5.0.0"));
    public static final Predicate<Version> isBelowES_6_X = belowMajorVersion(Version.fromString("ES 6.0.0"));
    public static final Predicate<Version> isBelowES_7_X = belowMajorVersion(Version.fromString("ES 7.0.0"));
    public static final Predicate<Version> isBelowES_8_X = belowMajorVersion(Version.fromString("ES 8.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_6_X = greaterOrEqualMajorVersion(Version.fromString("ES 6.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_7_X = greaterOrEqualMajorVersion(Version.fromString("ES 7.0.0"));
    public static final Predicate<Version> isGreaterOrEqualES_7_3 = greaterOrEqualMajorVersion(Version.fromString("ES 7.3.0"));
    public static final Predicate<Version> isGreaterOrEqualES_7_10 = greaterOrEqualMajorVersion(Version.fromString("ES 7.10.0"));
    public static final Predicate<Version> anyOS = VersionMatchers.matchesFlavor(Version.fromString("OS 1.0.0"));
    public static final Predicate<Version> isGreaterOrEqualOS_3_x = greaterOrEqualMajorVersion(Version.fromString("OS 3.0.0"));
    public static final Predicate<Version> equalOrGreaterThanOS_2_7 = VersionMatchers.equalOrGreaterThanMinorVersion(Version.fromString("OS 2.7.0"))
        .or(isGreaterOrEqualOS_3_x);
    public static final Predicate<Version> isAmazonServerlessOpenSearch = version -> 
        version != null && version.getFlavor() == Flavor.AMAZON_SERVERLESS_OPENSEARCH;

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

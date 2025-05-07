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
    public static final Predicate<Version> isOS_3_X = VersionMatchers.matchesMajorVersion(Version.fromString("OS 3.0.0"));
    public static final Predicate<Version> isOS_2_19_OrGreater = VersionMatchers.equalOrGreaterThanMinorVersion(Version.fromString("OS 2.19.0"))
                                                                    .or(VersionMatchers.isOS_3_X);

    public static final Predicate<Version> anyOS = VersionMatchers.isOS_1_X.or(VersionMatchers.isOS_2_X).or(VersionMatchers.isOS_3_X);

    public static final Predicate<Version> isBelowES_6_X = belowMajorVersion(Version.fromString("ES 6.0"));


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

    /**
     * Returns a predicate that checks if a given version is of the same flavor as the provided threshold version
     * and has a major version number that is lower than the threshold's major version.
     * This method ensures that only versions with a matching flavor are compared, thereby excluding incompatible OS or ES versions.
     *
     * @param version The threshold version used for comparison.
     * @return A predicate that returns {@code true} if the tested version's major version is less than the threshold and the flavors match.
     */
    private static Predicate<Version> belowMajorVersion(final Version version) {
        return other -> {
            if (other == null) {
                return false;
            }
            var flavorMatches = compatibleFlavor(other.getFlavor()).test(version.getFlavor());
            var isLowerMajorVersion = other.getMajor() < version.getMajor();
            return flavorMatches && isLowerMajorVersion;
        };
    }

}

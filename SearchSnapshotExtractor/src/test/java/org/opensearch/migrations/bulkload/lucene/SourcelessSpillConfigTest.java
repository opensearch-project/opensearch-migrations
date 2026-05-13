package org.opensearch.migrations.bulkload.lucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourcelessSpillConfig#maxOpenFieldSidecars()} resolution and
 * {@link SourcelessSpillConfig#autoMaxOpenFieldSidecars()} probing.
 *
 * <p>Each test pins one independently-checkable contract:
 * <ul>
 *   <li>{@link #autoCapMeetsFloor()}: auto-size never returns below the recommended floor.</li>
 *   <li>{@link #autoCapIsSensibleOnUnix()}: on a Unix HotSpot JVM (where the test runs in CI),
 *       auto-size derives from the soft fd limit and is &gt; the floor.</li>
 *   <li>{@link #explicitOverrideHonored()}: a positive integer in the system property wins
 *       over the auto path.</li>
 *   <li>{@link #overrideBelowFloorClampsUp()}: caps below {@code MIN} are clamped up to
 *       {@code MIN} so operators cannot pin a thrash-prone cap.</li>
 *   <li>{@link #autoLiteralForcesAutoPath()}: the literal string {@code "auto"} is treated
 *       the same as unset.</li>
 *   <li>{@link #invalidOverrideFallsBackToAuto()}: non-numeric values fall through to auto.</li>
 * </ul>
 */
class SourcelessSpillConfigTest {

    private static final String PROP = SourcelessSpillConfig.MAX_OPEN_FIELD_SIDECARS_PROP;
    private String savedProperty;

    @BeforeEach
    void saveProperty() {
        savedProperty = System.getProperty(PROP);
        System.clearProperty(PROP);
    }

    @AfterEach
    void restoreProperty() {
        if (savedProperty == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, savedProperty);
        }
    }

    @Test
    void autoCapMeetsFloor() {
        int auto = SourcelessSpillConfig.autoMaxOpenFieldSidecars();
        assertTrue(auto >= SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS,
                "auto cap " + auto + " must be >= MIN " + SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS);
    }

    @Test
    void autoCapIsSensibleOnUnix() {
        // On any reasonable build host the soft fd limit is at least 256, so cap >= 64.
        // This test guards against accidental regressions of the divisor or fallback path.
        int auto = SourcelessSpillConfig.autoMaxOpenFieldSidecars();
        assertTrue(auto >= 32,
                "auto cap " + auto + " is suspiciously small — auto-size probe likely broken");
        assertTrue(auto <= Integer.MAX_VALUE / 2,
                "auto cap " + auto + " is suspiciously large — divisor likely broken");
    }

    @Test
    void explicitOverrideHonored() {
        int n = SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS + 17;
        System.setProperty(PROP, Integer.toString(n));
        assertEquals(n, SourcelessSpillConfig.maxOpenFieldSidecars());
    }

    @Test
    void overrideBelowFloorClampsUp() {
        System.setProperty(PROP, "2");
        assertEquals(SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS,
                SourcelessSpillConfig.maxOpenFieldSidecars(),
                "values below MIN must clamp up so the LRU does not thrash");
    }

    @Test
    void overrideAtFloorPassesThrough() {
        System.setProperty(PROP, Integer.toString(SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS));
        assertEquals(SourcelessSpillConfig.MIN_MAX_OPEN_FIELD_SIDECARS,
                SourcelessSpillConfig.maxOpenFieldSidecars());
    }

    @Test
    void autoLiteralForcesAutoPath() {
        System.setProperty(PROP, "auto");
        int viaLiteral = SourcelessSpillConfig.maxOpenFieldSidecars();
        System.clearProperty(PROP);
        int viaUnset = SourcelessSpillConfig.maxOpenFieldSidecars();
        assertEquals(viaUnset, viaLiteral, "literal \"auto\" must take the same path as unset");
    }

    @Test
    void autoLiteralIsCaseInsensitive() {
        System.setProperty(PROP, "AUTO");
        int autoCap = SourcelessSpillConfig.autoMaxOpenFieldSidecars();
        assertEquals(autoCap, SourcelessSpillConfig.maxOpenFieldSidecars());
    }

    @Test
    void invalidOverrideFallsBackToAuto() {
        System.setProperty(PROP, "not-a-number");
        int autoCap = SourcelessSpillConfig.autoMaxOpenFieldSidecars();
        assertEquals(autoCap, SourcelessSpillConfig.maxOpenFieldSidecars(),
                "non-numeric override must fall back to auto, not crash");
    }

    @Test
    void blankOverrideUsesAuto() {
        System.setProperty(PROP, "   ");
        int autoCap = SourcelessSpillConfig.autoMaxOpenFieldSidecars();
        assertEquals(autoCap, SourcelessSpillConfig.maxOpenFieldSidecars());
    }
}

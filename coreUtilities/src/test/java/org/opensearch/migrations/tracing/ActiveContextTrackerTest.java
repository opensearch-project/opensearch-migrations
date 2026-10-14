package org.opensearch.migrations.tracing;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ActiveContextTrackerTest {

    @Test
    void contextsWithTheSameStartTimeRetainExactMembershipInBothIndexes() {
        assertExactMembershipForBothCloseOrders(false);
        assertExactMembershipForBothCloseOrders(true);
    }

    @Test
    void snapshotOrderingIsPrimarilyByStartTime() {
        var tracker = new ActiveContextTracker();
        var later = context(20, 1);
        var earlierSecond = context(10, 200);
        var earlierFirst = context(10, 100);

        tracker.onContextCreated(later);
        tracker.onContextCreated(earlierSecond);
        tracker.onContextCreated(earlierFirst);

        Assertions.assertEquals(
            List.of(earlierFirst, earlierSecond, later),
            tracker.getActiveScopesByAge().toList()
        );
    }

    private static void assertExactMembershipForBothCloseOrders(boolean closeSecondFirst) {
        var globalTracker = new ActiveContextTracker();
        var byActivityTracker = new ActiveContextTrackerByActivityType();
        var first = context(100, 1);
        var second = context(100, 2);

        addToBoth(globalTracker, byActivityTracker, first);
        addToBoth(globalTracker, byActivityTracker, second);
        assertContents(globalTracker, byActivityTracker, List.of(first, second));

        var firstToClose = closeSecondFirst ? second : first;
        var secondToClose = closeSecondFirst ? first : second;
        closeInBoth(globalTracker, byActivityTracker, firstToClose);
        assertContents(globalTracker, byActivityTracker, List.of(secondToClose));

        closeInBoth(globalTracker, byActivityTracker, secondToClose);
        assertContents(globalTracker, byActivityTracker, List.of());
    }

    private static IScopedInstrumentationAttributes context(long startTimeNano, long contextId) {
        var context = Mockito.mock(IScopedInstrumentationAttributes.class);
        Mockito.when(context.getStartTimeNano()).thenReturn(startTimeNano);
        Mockito.when(context.getContextId()).thenReturn(contextId);
        return context;
    }

    private static void addToBoth(
        ActiveContextTracker globalTracker,
        ActiveContextTrackerByActivityType byActivityTracker,
        IScopedInstrumentationAttributes context
    ) {
        globalTracker.onContextCreated(context);
        byActivityTracker.onContextCreated(context);
    }

    private static void closeInBoth(
        ActiveContextTracker globalTracker,
        ActiveContextTrackerByActivityType byActivityTracker,
        IScopedInstrumentationAttributes context
    ) {
        globalTracker.onContextClosed(context);
        byActivityTracker.onContextClosed(context);
    }

    @SuppressWarnings("unchecked")
    private static void assertContents(
        ActiveContextTracker globalTracker,
        ActiveContextTrackerByActivityType byActivityTracker,
        List<IScopedInstrumentationAttributes> expected
    ) {
        Assertions.assertEquals(expected, globalTracker.getActiveScopesByAge().toList());
        var activityType = (Class<IScopedInstrumentationAttributes>) expectedActivityClass(expected);
        if (activityType != null) {
            Assertions.assertEquals(expected, byActivityTracker.getOldestActiveScopes(activityType).toList());
        } else {
            Assertions.assertTrue(byActivityTracker.getActiveScopeTypes().findAny().isEmpty());
        }
    }

    private static Class<?> expectedActivityClass(List<IScopedInstrumentationAttributes> expected) {
        return expected.isEmpty() ? null : expected.get(0).getClass();
    }
}

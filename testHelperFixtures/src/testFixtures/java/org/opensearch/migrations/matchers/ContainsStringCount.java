package org.opensearch.migrations.matchers;

import lombok.AllArgsConstructor;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

@AllArgsConstructor
public class ContainsStringCount extends TypeSafeMatcher<String> {
    private final String expectedString;
    private final int expectedCount;

    @Override
    public void describeTo(Description description) {
        description.appendText("a string containing '" + expectedString + "' " + expectedCount + " times");
    }

    @Override
    protected void describeMismatchSafely(String item, Description mismatchDescription) {
        mismatchDescription.appendText("was found " + containsStringCount(item) + " times in '" + item + "'");
    }

    @Override
    protected boolean matchesSafely(String item) {
        return containsStringCount(item) == expectedCount;
    }

    private int containsStringCount(String item) {
        return item == null ? 0 : item.split(expectedString, -1).length - 1;
    }
    
    public static ContainsStringCount containsStringCount(String s, int n) {
        return new ContainsStringCount(s, n);
    }
}

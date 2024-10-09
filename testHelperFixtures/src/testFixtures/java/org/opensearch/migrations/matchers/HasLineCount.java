package org.opensearch.migrations.matchers;

import lombok.AllArgsConstructor;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

@AllArgsConstructor
public class HasLineCount extends TypeSafeMatcher<String> {
    private int expectedLineCount;

    @Override
    public void describeTo(Description description) {
        description.appendText("a string with " + expectedLineCount + " lines");
    }

    @Override
    protected void describeMismatchSafely(String item, Description mismatchDescription) {
        mismatchDescription.appendText("was a string with " + item.split(System.lineSeparator()).length + " lines in '" + item + "'");
    }

    @Override
    protected boolean matchesSafely(String item) {
        return newlineCount(item) == expectedLineCount;
    }

    private int newlineCount(String item) {
        return item == null ? 0 : item.split("\n").length;
    }

    public static HasLineCount hasLineCount(int n) {
        return new HasLineCount(n);
    }
}

package org.opensearch.migrations.transform.jinjava;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JavaRegexReplaceFilterTest {
    JavaRegexReplaceFilter replaceFilter = new JavaRegexReplaceFilter();

    String makeReplacementFromKnownMatch(String replacementPattern) {
        final var source = "Known Pattern 1234, and a note.";
        final var capturingPattern = "(([^ \\d]*) )*(\\d*)[^\\d]*(no.e)\\.";
        return (String) replaceFilter.filter(source, null, capturingPattern, replacementPattern);
    }

    @Test
    public void test() {
        Assertions.assertEquals("somethingNew", makeReplacementFromKnownMatch("somethingNew"));
        Assertions.assertEquals("Pattern$amount", makeReplacementFromKnownMatch("\\2$amount"));
        Assertions.assertEquals("Pattern$1", makeReplacementFromKnownMatch("\\2$1"));

        // other things to try
//        Assertions.assertEquals("Pattern\\$$1", makeReplacementFromKnownMatch("\\2\\$$\\1"));
//        Assertions.assertEquals("$1\\$amount", "\\1$amount", replacement);
//            "\\\\1$50",         // -> \\1\$50
//            "\\\\\\1$total$",   // -> \\$1\$total\$
//            "cost$1$price$2"    // -> cost$1\$price$2

    }
}

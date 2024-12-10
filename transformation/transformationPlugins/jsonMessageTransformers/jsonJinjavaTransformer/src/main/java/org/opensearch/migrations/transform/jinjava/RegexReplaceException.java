package org.opensearch.migrations.transform.jinjava;

import java.util.StringJoiner;

import lombok.Getter;

@Getter
public class RegexReplaceException extends RuntimeException {
    final String input;
    final String pattern;
    final String replacement;
    final String rewrittenReplacement;

    public RegexReplaceException(Throwable cause, String input, String pattern, String replacement, String rewrittenReplacement) {
        super(cause);
        this.input = input;
        this.pattern = pattern;
        this.replacement = replacement;
        this.rewrittenReplacement = rewrittenReplacement;
    }

    @Override
    public String getMessage() {
        return super.getMessage() +
            new StringJoiner(", ", "{", "}")
                .add("input='" + input + "'")
                .add("pattern='" + pattern + "'")
                .add("replacement='" + replacement + "'")
                .add("rewrittenReplacement='" + rewrittenReplacement + "'");
    }
}

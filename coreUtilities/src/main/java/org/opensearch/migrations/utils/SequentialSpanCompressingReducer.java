package org.opensearch.migrations.utils;

import lombok.AllArgsConstructor;

/**
 * This class can be used to reduce a stream of Integers into a string (calling getFinalAccumulation()).
 *
 * Example usage:
 * <pre>{@code
 * Stream<Integer> stream = ...
 * String result = stream.reduce(new SequentialSpanCompressingReducer(-1),
 *                                SequentialSpanCompressingReducer::addNext,
 *                                (c, d) -> { throw new IllegalStateException("parallel streams aren't allowed"); })
 *                      .getFinalAccumulation();
 * }</pre>
 */
@AllArgsConstructor
public class SequentialSpanCompressingReducer {
    private static final int IGNORED_SENTINEL_VALUE = -1;
    private static final char RUN_CHARACTER = '-';

    private final int shift;
    private final int last;
    private final StringBuilder accumulatedText;

    public SequentialSpanCompressingReducer(int shift) {
        this.shift = shift;
        this.last = IGNORED_SENTINEL_VALUE;
        this.accumulatedText = new StringBuilder();
    }

    private boolean lastWasSpan() {
        var len = accumulatedText.length();
        return len > 0 && accumulatedText.charAt(len - 1) == RUN_CHARACTER;
    }

    public SequentialSpanCompressingReducer addNext(int b) {
        if (last + shift == b) {
            if (lastWasSpan()) {
                return new SequentialSpanCompressingReducer(shift, b, accumulatedText);
            } else {
                return new SequentialSpanCompressingReducer(shift, b, accumulatedText.append(RUN_CHARACTER));
            }
        } else {
            if (lastWasSpan()) {
                return new SequentialSpanCompressingReducer(
                    shift,
                    b,
                    accumulatedText.append(last).append(",").append(b)
                );
            } else {
                return new SequentialSpanCompressingReducer(
                    shift,
                    b,
                    accumulatedText.append(last == IGNORED_SENTINEL_VALUE ? "" : ",").append(b)
                );
            }
        }
    }

    public String getFinalAccumulation() {
        if (lastWasSpan()) {
            accumulatedText.append(last);
        }
        return accumulatedText.toString();
    }
}

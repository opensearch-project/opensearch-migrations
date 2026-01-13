package org.opensearch.common.unit;

import java.util.concurrent.TimeUnit;

public class TimeValue implements Comparable<TimeValue> {
    public static final TimeValue MINUS_ONE = timeValueMillis(-1);
    public static final TimeValue ZERO = timeValueMillis(0);
    public static final TimeValue MAX_VALUE = timeValueNanos(Long.MAX_VALUE);

    private final long duration;
    private final TimeUnit timeUnit;

    public TimeValue(long millis) { this(millis, TimeUnit.MILLISECONDS); }
    public TimeValue(long duration, TimeUnit timeUnit) { this.duration = duration; this.timeUnit = timeUnit; }

    public static TimeValue timeValueNanos(long nanos) { return new TimeValue(nanos, TimeUnit.NANOSECONDS); }
    public static TimeValue timeValueMillis(long millis) { return new TimeValue(millis, TimeUnit.MILLISECONDS); }
    public static TimeValue timeValueSeconds(long seconds) { return new TimeValue(seconds, TimeUnit.SECONDS); }
    public static TimeValue timeValueMinutes(long minutes) { return new TimeValue(minutes, TimeUnit.MINUTES); }
    public static TimeValue timeValueHours(long hours) { return new TimeValue(hours, TimeUnit.HOURS); }
    public static TimeValue timeValueDays(long days) { return new TimeValue(days, TimeUnit.DAYS); }

    public TimeUnit timeUnit() { return timeUnit; }
    public long duration() { return duration; }
    public long nanos() { return timeUnit.toNanos(duration); }
    public long getNanos() { return nanos(); }
    public long micros() { return timeUnit.toMicros(duration); }
    public long getMicros() { return micros(); }
    public long millis() { return timeUnit.toMillis(duration); }
    public long getMillis() { return millis(); }
    public long seconds() { return timeUnit.toSeconds(duration); }
    public long getSeconds() { return seconds(); }
    public long minutes() { return timeUnit.toMinutes(duration); }
    public long getMinutes() { return minutes(); }
    public long hours() { return timeUnit.toHours(duration); }
    public long getHours() { return hours(); }
    public long days() { return timeUnit.toDays(duration); }
    public long getDays() { return days(); }

    public String getStringRep() { return duration + "ms"; }
    @Override public String toString() { return getStringRep(); }

    public static TimeValue parseTimeValue(String sValue, String settingName) { return new TimeValue(0); }
    public static TimeValue parseTimeValue(String sValue, TimeValue defaultValue, String settingName) { return defaultValue != null ? defaultValue : new TimeValue(0); }

    @Override public int compareTo(TimeValue o) { return Long.compare(nanos(), o.nanos()); }
    @Override public boolean equals(Object o) { return o instanceof TimeValue && compareTo((TimeValue) o) == 0; }
    @Override public int hashCode() { return Long.hashCode(nanos()); }
}

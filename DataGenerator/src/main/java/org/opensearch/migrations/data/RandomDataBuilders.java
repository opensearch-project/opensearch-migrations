package org.opensearch.migrations.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import lombok.experimental.UtilityClass;

/** Shared ways to build random data */
@UtilityClass
public class RandomDataBuilders {
    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");
    private static final DateTimeFormatter SIMPLE_DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    public static long randomTime(long timeFrom, Random random) {
        return timeFrom - random.nextInt(ONE_DAY_IN_MILLIS);
    }

    public static String randomTimeISOString(long timeFrom, Random random) {
        var timeMillis = randomTime(timeFrom, random);
        var timeInstant = Instant.ofEpochMilli(timeMillis).atZone(UTC_ZONE);
        return SIMPLE_DATE_PATTERN.format(timeInstant);
    }

    public static double randomDouble(Random random, double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    public static String randomElement(String[] elements, Random random) {
        return elements[random.nextInt(elements.length)];
    }

    public static int randomElement(int[] elements, Random random) {
        return elements[random.nextInt(elements.length)];
    }
}

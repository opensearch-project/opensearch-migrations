package org.opensearch.migrations.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/** Shared ways to build random data */
public interface IRandomDataBuilders {
    ZoneId UTC_ZONE = ZoneId.of("UTC");
    DateTimeFormatter SIMPLE_DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    default long randomTime(long timeFrom, Random random) {
        return timeFrom - random.nextInt(ONE_DAY_IN_MILLIS);
    }

    default String randomTimeISOString(long timeFrom, Random random) {
        var timeMillis = randomTime(timeFrom, random);
        var timeInstant = Instant.ofEpochMilli(timeMillis).atZone(UTC_ZONE);
        return SIMPLE_DATE_PATTERN.format(timeInstant);
    }

    default double randomDouble(Random random, double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    default String randomElement(String[] elements, Random random) {
        return elements[random.nextInt(elements.length)];
    }

    default int randomElement(int[] elements, Random random) {
        return elements[random.nextInt(elements.length)];
    }
}

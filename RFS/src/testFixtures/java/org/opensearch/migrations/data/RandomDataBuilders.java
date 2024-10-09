package org.opensearch.migrations.data;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RandomDataBuilders {
    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    public static long randomTime(long timeFrom, Random random) {
        return timeFrom - random.nextInt(ONE_DAY_IN_MILLIS);
    }

    public static String randomTimeISOString(long timeFrom, Random random) {
        var timeMillis = randomTime(timeFrom, random);
        return DateTimeFormatter.ISO_DATE_TIME.format(Instant.ofEpochMilli(timeMillis));
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

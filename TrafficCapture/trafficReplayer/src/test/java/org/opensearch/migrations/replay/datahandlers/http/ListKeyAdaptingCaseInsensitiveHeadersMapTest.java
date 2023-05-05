package org.opensearch.migrations.replay.datahandlers.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListKeyAdaptingCaseInsensitiveHeadersMapTest {

    public static final String COOKIE = "cookie";
    public static final String CAPITAL_COOKIE = "Cookie";
    public static final String OATMEAL_RAISON = "oatmealRaison";
    public static final String DOUBLE_CHOCOLATE = "doubleChocolate";
    public static final String PEANUT_BUTTER = "peanutButter";

    @Test
    public void testPutCanAdapt() {
        var strictMap = new StrictCaseInsensitiveHttpHeadersMap();
        var looseMap = new ListKeyAdaptingCaseInsensitiveHeadersMap(strictMap);
        strictMap.put(COOKIE, new ArrayList<>());
        strictMap.get(COOKIE).add(DOUBLE_CHOCOLATE);
        strictMap.get(COOKIE).add(PEANUT_BUTTER);
        Assertions.assertEquals(2, strictMap.get(COOKIE).size());
        Assertions.assertEquals(2, ((List)(looseMap.get(COOKIE))).size());

        looseMap.put(CAPITAL_COOKIE, OATMEAL_RAISON);

        Assertions.assertEquals(1, strictMap.get(COOKIE).size());
        Assertions.assertEquals(OATMEAL_RAISON, strictMap.get(COOKIE).get(0));

        strictMap.get(COOKIE).add(DOUBLE_CHOCOLATE);

        Assertions.assertEquals(2, strictMap.get(COOKIE).size());
        Assertions.assertEquals(OATMEAL_RAISON, strictMap.get(COOKIE).get(0));
        Assertions.assertEquals(DOUBLE_CHOCOLATE, strictMap.get(COOKIE).get(1));
    }
}
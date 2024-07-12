package org.opensearch.migrations.replay.datahandlers.http;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class ListKeyAdaptingCaseInsensitiveHeadersMapTest {

    private static final String COOKIE = "cookie";
    private static final String CAPITAL_COOKIE = "Cookie";
    private static final String OATMEAL_RAISON = "oatmealRaison";
    private static final String DOUBLE_CHOCOLATE = "doubleChocolate";
    private static final String PEANUT_BUTTER = "peanutButter";
    private static final String COOKIE_CRUMBLES = "crumbs";
    private static final int BEEF_NUM = 0xbeef;

    @Test
    public void testPutCanAdapt() {
        var strictMap = new StrictCaseInsensitiveHttpHeadersMap();
        var looseMap = new ListKeyAdaptingCaseInsensitiveHeadersMap(strictMap);
        strictMap.put(COOKIE, new ArrayList<>());
        strictMap.get(COOKIE).add(DOUBLE_CHOCOLATE);
        strictMap.get(COOKIE).add(PEANUT_BUTTER);
        Assertions.assertEquals(2, strictMap.get(COOKIE).size());
        Assertions.assertEquals(2, ((List) (looseMap.get(COOKIE))).size());

        looseMap.put(CAPITAL_COOKIE, OATMEAL_RAISON);

        Assertions.assertEquals(1, strictMap.get(COOKIE).size());
        Assertions.assertEquals(OATMEAL_RAISON, strictMap.get(COOKIE).get(0));

        strictMap.get(COOKIE).add(DOUBLE_CHOCOLATE);

        Assertions.assertEquals(2, strictMap.get(COOKIE).size());
        Assertions.assertEquals(OATMEAL_RAISON, strictMap.get(COOKIE).get(0));
        Assertions.assertEquals(DOUBLE_CHOCOLATE, strictMap.get(COOKIE).get(1));

        var oldList = looseMap.put(
            COOKIE,
            List.of(Integer.valueOf(BEEF_NUM), Integer.valueOf(BEEF_NUM), COOKIE_CRUMBLES)
        );
        Assertions.assertEquals(2, oldList.size());
        Assertions.assertEquals(3, strictMap.get(COOKIE).size());
        var beefStr = Integer.valueOf(BEEF_NUM).toString();
        Assertions.assertEquals(beefStr, strictMap.get(COOKIE).get(0));
        Assertions.assertEquals(beefStr, strictMap.get(COOKIE).get(1));
        Assertions.assertEquals(COOKIE_CRUMBLES, strictMap.get(COOKIE).get(2));
        Assertions.assertEquals(
            3,
            ((List) ((AbstractMap.SimpleEntry) looseMap.entrySet().toArray()[0]).getValue()).size()
        );

        looseMap.put(COOKIE, List.of(COOKIE_CRUMBLES));
        Assertions.assertEquals(1, strictMap.get(COOKIE).size());
        Assertions.assertEquals(COOKIE_CRUMBLES, strictMap.get(COOKIE).get(0));
    }
}

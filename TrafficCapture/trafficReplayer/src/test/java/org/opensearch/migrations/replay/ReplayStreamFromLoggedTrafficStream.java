//package org.opensearch.migrations.replay;
//
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.Arguments;
//import org.junit.jupiter.params.provider.MethodSource;
//import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
//
//import java.io.ByteArrayInputStream;
//import java.io.IOException;
//import java.util.Base64;
//import java.util.stream.Stream;
//import java.util.zip.GZIPInputStream;
//
//public class ReplayStreamFromLoggedTrafficStream {
//    static Arguments[] getTestEncodedStrings() {
//        return Stream.of(
//                "H4sIAAAAAAAAALXWTWvbMBgAYNajwZeeehQ+bSUfluVv2CFN2xWWMZN4LWWMoilq6tWVjOWMpqW3wcYG+w277Yf0j012PkiwGxLTkYOj95Ut8/jVx+7vHe3XjvJjJ3g/CEH74vM4vgYnYRi0YQuqygkXmQ8ITrJxSptJym8nTSp8z9B1VSGcZZRlzWySUB/gJIkjgrOIs/YXwZmqjAVNm3gku/iAJ5QJilNy1UwmbaNltHTwMphkV5wB1HJbUH+lKngs22l0VzzEBwdYRAScn/UzfGbas+trVelyxiiZ9rmmNGniOPpKVaVDCE3kWPvt/XmjecQIH0Zs5IPRXZQ0wJBexjijxUOKl+9RNsqufIB0x1YVVdn9uad931O+7d1rERvSW80H99rF/K82opzhGyq0hwflft6Khlr+AOS6htUAWh7K+3Z5HMsBwWkkMqzJBBYkip7MXlKcK1+QGAuR9whXonxY3PamE+RhwscsSyeLcOewGGB4EzG4COp2Hkx4Mo4LUhnTZWBIb/KsgZCV57Poht5xVtxxNE7ll2p32JCnafFWMSfzez/Clq1bjtsAptEyHRvCTzlCHSa7PhOOM5oy+Q2nA9SXDDqDwdOUhBjTRuNoUEWrr6OFnu1sS2ua0EEzWssyatM6S7RSBMtpxyYl1tVMibRIE5lurHQswfaqYHvd8HzzGjXWQjrTGt6qRqH8FZCWY7pubUh3CbLPSV5hMZZFxiblKq3Ib1aE/bebS6G1Uoatbytl2aY5kzIcx64t5S3PZsxwvNaqskdJ66RKaxC+25zLXMsFXbQ1l4GgN+MyUO0ZivQlrgMuyHTtimOclqzK6RLUaRXUcX8Qbi5lrZVyaxSWicwCytYhsmpDwSWoUI4k9+sZRrmqKvL/oabWT0GEtl71LduebagWMqFZm8pYWfUrfBbB8vaJBRYFWuNJuaByAw16zyYH3RpyljmTg/UXL4SWZyOWR1YxrSG5+z3+LU/I1R5k85W+2zt+poMb1KG39emiWKWn5zbbq41lLmEFcv0uHB7/iJLTIonF5kQfgt7hMx0cDN0ytj44WJ432w49Xc8Pt+DFP9TTofcgDQAA",
//                )
//                .map(s->Arguments.of(s))
//                .toArray();
//        )
//    }
//    @ParameterizedTest
//    @MethodSource("getTestEncodedStrings")
//    public void replayLoggedStream(String encodedAndZippedStr) throws Exception {
//        try (var bais = new ByteArrayInputStream(Base64.getDecoder().decode(encodedAndZippedStr))) {
//            try (var gzis = new GZIPInputStream(bais)) {
//                TrafficStream.parseDelimitedFrom(gzis);
//            }
//        }
//    }
//}

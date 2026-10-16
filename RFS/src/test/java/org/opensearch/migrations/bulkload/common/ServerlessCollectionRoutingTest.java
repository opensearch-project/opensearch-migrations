package org.opensearch.migrations.bulkload.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerlessCollectionRoutingTest {

    private static ServerlessCollectionRouting parse(String json) {
        return ServerlessCollectionRouting.fromJson(json).orElseThrow();
    }

    private static String regexTable(String sourceIndex, String collection) {
        return "{\"regexCollectionRouting\": [{\"sourceIndex\": \"" + sourceIndex
            + "\", \"collection\": \"" + collection + "\"}]}";
    }

    @Test
    void staticEntriesWinOverRegexEntries_regexEntriesAreOrdered() {
        var routing = parse("""
            {
              "regexCollectionRouting": [
                {"sourceIndex": "(.+)[-_]\\\\d{4}", "collection": "$1"},
                {"sourceIndex": ".*", "collection": "fallback"}
              ],
              "staticCollectionRouting": [
                {"sourceIndex": "shared-config", "collection": "common"},
                {"sourceIndex": "tenant-a-2024", "collection": "pinned"}
              ]
            }""");

        assertEquals(Optional.of("common"), routing.resolve("shared-config"));
        assertEquals(Optional.of("pinned"), routing.resolve("tenant-a-2024"));
        assertEquals(Optional.of("tenant-b"), routing.resolve("tenant-b-2024"));
        assertEquals(Optional.of("tenant_c"), routing.resolve("tenant_c_2023"));
        assertEquals(Optional.of("fallback"), routing.resolve("other"));
    }

    @Test
    void staticCollectionIsUsedLiterally() {
        var routing = parse("""
            {"staticCollectionRouting": [{"sourceIndex": "logs", "collection": "$1"}]}""");

        assertEquals(Optional.of("$1"), routing.resolve("logs"));
    }

    @Test
    void namedGroupsAndLiteralTextInCollection() {
        var routing = parse(regexTable("logs-(?<app>[a-z]+)-.*", "col-${app}"));

        assertEquals(Optional.of("col-web"), routing.resolve("logs-web-2024.01"));
    }

    @Test
    void escapedDollarIsLiteral() {
        var routing = parse(regexTable("(a)", "x\\\\$1"));

        assertEquals(Optional.of("x$1"), routing.resolve("a"));
    }

    @Test
    void regexMustMatchWholeIndexName() {
        var routing = parse(regexTable("logs", "logs"));

        assertEquals(Optional.empty(), routing.resolve("logs-2024"));
        assertEquals(Optional.of("logs"), routing.resolve("logs"));
    }

    @Test
    void resolveRequired_failsForUnmatchedIndex() {
        var routing = parse("""
            {"staticCollectionRouting": [{"sourceIndex": "a", "collection": "c"}]}""");

        var e = assertThrows(IllegalArgumentException.class, () -> routing.resolveRequired("b"));
        assertTrue(e.getMessage().contains("'b'"), e.getMessage());
    }

    @Test
    void resolveAll_returnsDistinctCollectionsInOrder() {
        var routing = parse(regexTable("(.+)-\\\\d+", "$1"));

        assertEquals(List.of("b", "a"), List.copyOf(routing.resolveAll(List.of("b-1", "a-1", "b-2"))));
    }

    @Test
    void resolveAll_reportsEveryUnmatchedIndex() {
        var routing = parse("""
            {"staticCollectionRouting": [{"sourceIndex": "a", "collection": "c"}]}""");

        var e = assertThrows(IllegalArgumentException.class, () -> routing.resolveAll(List.of("a", "x", "y")));
        assertTrue(e.getMessage().contains("[x, y]"), e.getMessage());
    }

    @Test
    void emptyResolvedCollection_fails() {
        var routing = parse(regexTable("x(.*)", "$1"));

        assertThrows(IllegalArgumentException.class, () -> routing.resolve("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "[]",
        "{}",
        "{\"staticCollectionRouting\": [], \"regexCollectionRouting\": []}",
        "{\"collectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}",
        "{\"staticCollectionRouting\": {\"a\": \"c\"}}",
        "{\"staticCollectionRouting\": [{\"collection\": \"c\"}]}",
        "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\"}]}",
        "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"\"}]}",
        "{\"staticCollectionRouting\": [{\"sourceIndex\": 1, \"collection\": \"c\"}]}",
        "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(\", \"collection\": \"c\"}]}"
    })
    void invalidTables_areRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> ServerlessCollectionRouting.fromJson(json));
    }

    @Test
    void duplicateStaticSourceIndex_isRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> ServerlessCollectionRouting.fromJson("""
            {"staticCollectionRouting": [
              {"sourceIndex": "a", "collection": "c1"},
              {"sourceIndex": "a", "collection": "c2"}
            ]}"""));
        assertTrue(e.getMessage().contains("'a' more than once"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "$2",           // only one group
        "${missing}",   // no such named group
        "${app",        // unclosed name
        "a$",           // trailing dollar
        "$x",           // not a group reference
        "a\\\\"         // trailing backslash
    })
    void invalidReplacements_areRejectedWhenParsed(String collection) {
        var json = "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(?<app>a)\", \"collection\": \""
            + collection + "\"}]}";
        var e = assertThrows(IllegalArgumentException.class, () -> ServerlessCollectionRouting.fromJson(json));
        assertTrue(e.getMessage().contains("regexCollectionRouting"), e.getMessage());
    }

    @Test
    void blankTable_meansNoRouting() {
        assertEquals(Optional.empty(), ServerlessCollectionRouting.fromJson(null));
        assertEquals(Optional.empty(), ServerlessCollectionRouting.fromJson("  "));
    }

    @Test
    void forTarget_requiresTableExactlyForRoutedTargets() {
        var table = "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}";

        assertTrue(ServerlessCollectionRouting.forTarget(table, true).isPresent());
        assertTrue(ServerlessCollectionRouting.forTarget(null, false).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ServerlessCollectionRouting.forTarget(null, true));
        assertThrows(IllegalArgumentException.class, () -> ServerlessCollectionRouting.forTarget(table, false));
    }

    @Test
    void headersFor_namesTheCollection() {
        assertEquals(Map.of("x-amz-aoss-collection-name", List.of("c")), ServerlessCollectionRouting.headersFor("c"));
        assertNull(ServerlessCollectionRouting.headersFor(null));
    }

    @Test
    void collectionRoutedTarget_decidesServerGeneratedIdsOncePerCollection() {
        var routing = parse(regexTable("(.+)-\\\\d+", "$1"));
        var probed = new ArrayList<String>();
        var target = new CollectionRoutedTarget(routing, collection -> {
            probed.add(collection);
            return collection.equals("ts");
        });

        assertEquals("ts", target.collectionFor("ts-1"));
        target.prepare("ts-1");
        assertTrue(target.allowServerGeneratedIds("ts"));
        assertTrue(target.allowServerGeneratedIds("ts"));
        assertEquals(false, target.allowServerGeneratedIds("search"));
        assertEquals(List.of("ts", "search"), probed);
        target.validateSourceIndices(List.of("ts-1", "search-2"));
        assertThrows(IllegalArgumentException.class, () -> target.validateSourceIndices(List.of("ts-1", "nope")));
        assertEquals(Set.of("ts"), routing.resolveAll(List.of("ts-1")));
    }
}

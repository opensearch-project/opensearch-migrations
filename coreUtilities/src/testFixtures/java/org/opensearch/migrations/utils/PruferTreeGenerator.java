package org.opensearch.migrations.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * See https://en.wikipedia.org/wiki/Prufer_sequence
 * @param <T>
 */
@Slf4j
public class PruferTreeGenerator<T> {
    @Builder
    @ToString
    private static class Link {
        int from;
        int to;
    }

    public static class SimpleNode<T> {
        public final T value;

        public SimpleNode(T value) {
            this.value = value;
        }

        List<SimpleNode<T>> children;

        public boolean hasChildren() {
            return children != null && !children.isEmpty();
        }

        public Stream<SimpleNode<T>> getChildren() {
            return children == null ? Stream.of() : children.stream();
        }
    }

    public interface NodeValueGenerator<T> {
        T makeValue(int vertexNumber);
    }

    public interface Visitor<T> {
        void pushTreeNode(SimpleNode<T> node);

        void popTreeNode(SimpleNode<T> node);
    }

    public void preOrderVisitTree(SimpleNode<T> treeNode, Visitor<T> visitor) {
        visitor.pushTreeNode(treeNode);
        if (treeNode.hasChildren()) {
            treeNode.children.forEach(child -> preOrderVisitTree(child, visitor));
        }
        visitor.popTreeNode(treeNode);
    }

    public SimpleNode<T> makeTree(NodeValueGenerator<T> nodeGenerator, int... pruferSequence) {
        List<Link> edges = makeLinks(pruferSequence);
        return convertLinksToTree(nodeGenerator, edges);
    }

    private SimpleNode<T> getMemoizedNode(
        TreeMap<Integer, SimpleNode<T>> nodeMap,
        HashSet<SimpleNode<T>> nodesWithoutParents,
        int key,
        IntFunction<T> valueGenerator
    ) {
        return nodeMap.computeIfAbsent(key, k -> {
            var n = new SimpleNode<>(valueGenerator.apply(k));
            nodesWithoutParents.add(n);
            return n;
        });
    }

    private SimpleNode<T> convertLinksToTree(NodeValueGenerator<T> valueGenerator, List<Link> edges) {
        var nodeMap = new TreeMap<Integer, SimpleNode<T>>();
        var nodesWithoutParents = new HashSet<SimpleNode<T>>();
        edges.stream().forEach(e -> {
            var childNode = getMemoizedNode(nodeMap, nodesWithoutParents, e.from, valueGenerator::makeValue);
            var parent = getMemoizedNode(nodeMap, nodesWithoutParents, e.to, valueGenerator::makeValue);
            if (parent.children == null) {
                parent.children = new ArrayList<>();
            }
            parent.children.add(childNode);
            nodesWithoutParents.remove(childNode);
        });
        return nodeMap.lastEntry().getValue();
    }

    /**
     * Implement the algorithm as shown in https://www.youtube.com/watch?v=7s44l7gWEVk.
     *
     * There are 3 interesting number of degrees left to connect for each node.
     * 0: No more items in the remaining pruferSequenceArray will link to this item
     * 1: Exactly one more will link to it.  The index will NOT be found in pruferSequenceArray BUT
     *    the next value in pruferSequenceArray will have a link node whose index is the first item == 1
     *    in this set
     * >1: This indicates how many more instances will be found in the pruferSequenceArray and
     *     will be decremented each time that an item is found
     * @param pruferSequenceArray
     * @return
     */
    private List<Link> makeLinks(final int[] pruferSequenceArray) {
        int pruferLen = pruferSequenceArray.length;
        List<Link> edges = new ArrayList<>();
        // ids are sequential integers. We will map them to something else outside of this function.
        // Using just ints here are more convenient because we can use them as keys to do lookups in arrays.
        int numNodeIds = pruferLen + 2;
        // This collection captures items with degree > 1
        var nodeDegrees = Arrays.stream(pruferSequenceArray)
            // initialize the nodeDegrees values to the number of occurrences (inflows) + 1
            .mapToObj(i -> i)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        // This collection captures items with degree == 1 with a PQueue because we only ever need to get the top item
        var nodesWithOneLinkLeft = new PriorityQueue<Integer>(
            IntStream.range(1, numNodeIds + 1)
                .filter(i -> nodeDegrees.get(i) == null)
                .boxed()
                .collect(Collectors.toList())
        );

        for (int i = 0; i < pruferLen; i++) {
            var parent = pruferSequenceArray[i];
            var child = nodesWithOneLinkLeft.poll();
            if (log.isTraceEnabled()) {
                log.trace("Adding link from {} -> {}", child, parent);
            }
            edges.add(Link.builder().from(child).to(parent).build());
            removeLinkForNode(parent, nodeDegrees, nodesWithOneLinkLeft);
            if (log.isTraceEnabled()) {
                log.trace("degrees: " + arrayAsString(nodeDegrees));
            }
        }

        edges.add(Link.builder().from(nodesWithOneLinkLeft.poll()).to(nodesWithOneLinkLeft.poll()).build());
        return edges;
    }

    private void removeLinkForNode(
        int parent,
        Map<Integer, Long> nodeDegrees,
        PriorityQueue<Integer> nodesWithOneLinkLeft
    ) {
        var oldDegreeVal = nodeDegrees.get(parent);
        if (oldDegreeVal == 1) {
            nodeDegrees.remove(parent);
            nodesWithOneLinkLeft.add(parent);
        } else {
            nodeDegrees.put(parent, oldDegreeVal - 1);
        }
    }

    private static String arrayAsString(Map<Integer, Long> nodeDegrees) {
        return nodeDegrees.entrySet().stream().map(Object::toString).collect(Collectors.joining(","));
    }

}

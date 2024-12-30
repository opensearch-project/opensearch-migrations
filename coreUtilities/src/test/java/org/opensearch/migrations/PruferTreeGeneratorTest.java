package org.opensearch.migrations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.stream.IntStream;

import org.opensearch.migrations.utils.PruferTreeGenerator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class PruferTreeGeneratorTest {

    @Test
    public void testThatArbitrarySequencePrintsExpectedTree() throws IOException {
        PruferTreeGenerator ptg = new PruferTreeGenerator<String>();
        Random random = new Random(5);
        final int numNodes = 5;
        var edges = IntStream.range(0, numNodes - 2).map(x -> random.nextInt(numNodes) + 1).toArray();
        var tree = ptg.makeTree(vn -> Integer.toString(vn), edges);
        try (
            var baos = new ByteArrayOutputStream();
            var printStream = new PrintStream(baos, false, StandardCharsets.UTF_8)
        ) {
            printTree(ptg, printStream, tree);
            printStream.flush();
            var expectedOutput = "5: { 3: {  1  2 } 4}";
            var generatedOutput = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            Assertions.assertEquals(expectedOutput, generatedOutput);
        }
    }

    private static void printTree(
        PruferTreeGenerator<String> ptg,
        PrintStream out,
        PruferTreeGenerator.SimpleNode<String> tree
    ) {
        ptg.preOrderVisitTree(tree, new PruferTreeGenerator.Visitor<String>() {
            int nodeDepth;

            @Override
            public void pushTreeNode(PruferTreeGenerator.SimpleNode<String> node) {
                out.print(" ".repeat(nodeDepth++) + node.value + (node.hasChildren() ? ": {" : ""));
            }

            @Override
            public void popTreeNode(PruferTreeGenerator.SimpleNode<String> node) {
                nodeDepth--;
                if (node.hasChildren()) {
                    out.print(" ".repeat(nodeDepth) + "}");
                }
            }
        });
    }

}

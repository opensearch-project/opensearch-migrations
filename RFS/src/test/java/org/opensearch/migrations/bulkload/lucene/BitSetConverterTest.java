package org.opensearch.migrations.bulkload.lucene;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

public class BitSetConverterTest {

    private static final int LARGE_BIT_SET_SIZE = 1_000_000;
    private static final double BIT_DENSITY = 0.35; // 35% of bits set
    private static final long RANDOM_SEED = 42L; // Fixed seed for reproducibility

    /**
     * Test case holder for each Lucene version
     */
    static class LuceneVersionTestCase {
        final String version;
        final String packageName;
        final Class<?> bitsClass;
        final Class<?> fixedBitSetClass;
        final Class<?> sparseFixedBitSetClass;
        final Function<Object, long[]> getBitsFromFixed;
        final ToIntFunction<Object> getLength;
        final Function<Object, ToIntFunction<Integer>> nextSetBitFactory;

        LuceneVersionTestCase(String version) throws ClassNotFoundException {
            this.version = version;
            this.packageName = "shadow.lucene" + version + ".org.apache.lucene.util";
            
            // Load classes using reflection
            this.bitsClass = Class.forName(packageName + ".Bits");
            this.fixedBitSetClass = Class.forName(packageName + ".FixedBitSet");
            this.sparseFixedBitSetClass = Class.forName(packageName + ".SparseFixedBitSet");
            
            // Create function wrappers for the methods we need
            this.getBitsFromFixed = obj -> {
                try {
                    Method getBitsMethod = fixedBitSetClass.getMethod("getBits");
                    return (long[]) getBitsMethod.invoke(obj);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get bits from FixedBitSet", e);
                }
            };
            
            this.getLength = obj -> {
                try {
                    Method lengthMethod = bitsClass.getMethod("length");
                    return (int) lengthMethod.invoke(obj);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get length from Bits", e);
                }
            };
            
            this.nextSetBitFactory = sparseBits -> idx -> {
                try {
                    Method nextSetBitMethod = sparseFixedBitSetClass.getMethod("nextSetBit", int.class);
                    return (int) nextSetBitMethod.invoke(sparseBits, idx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to call nextSetBit", e);
                }
            };
        }

        Object createFixedBitSet(int numBits) throws Exception {
            Constructor<?> constructor = fixedBitSetClass.getConstructor(int.class);
            return constructor.newInstance(numBits);
        }

        Object createSparseFixedBitSet(int numBits) throws Exception {
            Constructor<?> constructor = sparseFixedBitSetClass.getConstructor(int.class);
            return constructor.newInstance(numBits);
        }

        void setBit(Object bitSet, int index) throws Exception {
            Method setMethod = bitSet.getClass().getMethod("set", int.class);
            setMethod.invoke(bitSet, index);
        }

        @SneakyThrows
        boolean getBit(Object bits, int index) {
            Method getMethod = bitsClass.getMethod("get", int.class);
            return (boolean) getMethod.invoke(bits, index);
        }

        @SneakyThrows
        BiPredicate<Object, Integer> createGetBitBiPredicate() {
            Method getMethod = bitsClass.getMethod("get", int.class);
            return (Object _bits, Integer idx) -> {
                try {
                    return (boolean) getMethod.invoke(_bits, idx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke Bits#get(int)", e);
                }
            };
        }

        /**
         * Creates a custom Bits implementation using dynamic proxy
         */
        Object createCustomBits(Predicate<Integer> getBitPredicate, int length) {
            return Proxy.newProxyInstance(
                bitsClass.getClassLoader(),
                new Class<?>[] { bitsClass },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get":
                            return getBitPredicate.test((Integer) args[0]);
                        case "length":
                            return length;
                        default:
                            throw new UnsupportedOperationException("Method not implemented: " + method.getName());
                    }
                }
            );
        }
        
        
        /**
         * Converts a Lucene Bits object to BitSetConverter.LengthDisabledBitSet
         * This method encapsulates the common conversion logic to reduce duplication
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        @SneakyThrows
        BitSetConverter.FixedLengthBitSet convertBits(Object bits) {
            return BitSetConverter.convert(
                bits,
                (Class) fixedBitSetClass,
                (Class) sparseFixedBitSetClass,
                getBitsFromFixed,
                getLength,
                createGetBitBiPredicate(),
                nextSetBitFactory
            );
        }
        
        /**
         * Converts with null classes to test fallback behavior
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        BitSetConverter.FixedLengthBitSet convertBitsWithNullClasses(Object bits) {
            return BitSetConverter.convert(
                bits,
                null,  // No FixedBitSet class
                null,  // No SparseFixedBitSet class
                obj -> new long[0],  // Won't be used
                getLength,
                createGetBitBiPredicate(),
                null  // No nextSetBit function
            );
        }
    }

    /**
     * Provides test cases for all supported Lucene versions
     */
    static Stream<Arguments> luceneVersionProvider() {
        return Stream.of(
            Arguments.of("5"),
            Arguments.of("6"),
            Arguments.of("7"),
            Arguments.of("9")
        );
    }

    /**
     * Test null input conversion across all versions
     */
    @ParameterizedTest(name = "Lucene {0} - Null Input")
    @MethodSource("luceneVersionProvider")
    void testConvertNull(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(null);
        
        assertNull(result, "Converting null should return null for Lucene " + version);
    }

    /**
     * Test FixedBitSet conversion
     */
    @ParameterizedTest(name = "Lucene {0} - FixedBitSet Conversion")
    @MethodSource("luceneVersionProvider")
    void testConvertFixedBitSet(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a FixedBitSet with some bits set
        Object fixedBitSet = testCase.createFixedBitSet(10);
        testCase.setBit(fixedBitSet, 0);
        testCase.setBit(fixedBitSet, 3);
        testCase.setBit(fixedBitSet, 7);
        testCase.setBit(fixedBitSet, 9);
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(fixedBitSet);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.get(0));
        assertFalse(result.get(1));
        assertFalse(result.get(2));
        assertTrue(result.get(3));
        assertFalse(result.get(4));
        assertFalse(result.get(5));
        assertFalse(result.get(6));
        assertTrue(result.get(7));
        assertFalse(result.get(8));
        assertTrue(result.get(9));
        
        // Verify cardinality
        assertEquals(4, result.cardinality(), "Should have 4 bits set");
    }

    /**
     * Test SparseFixedBitSet conversion
     */
    @ParameterizedTest(name = "Lucene {0} - SparseFixedBitSet Conversion")
    @MethodSource("luceneVersionProvider")
    void testConvertSparseFixedBitSet(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a SparseFixedBitSet with some bits set
        Object sparseBitSet = testCase.createSparseFixedBitSet(100);
        testCase.setBit(sparseBitSet, 5);
        testCase.setBit(sparseBitSet, 25);
        testCase.setBit(sparseBitSet, 50);
        testCase.setBit(sparseBitSet, 99);
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(sparseBitSet);
        
        // Verify the result
        assertNotNull(result);
        assertFalse(result.get(0));
        assertTrue(result.get(5));
        assertTrue(result.get(25));
        assertTrue(result.get(50));
        assertTrue(result.get(99));
        assertFalse(result.get(98));
        assertFalse(result.get(100));


        // Verify cardinality
        assertEquals(4, result.cardinality(), "Should have 4 bits set in sparse bitset");
    }

    /**
     * Test custom Bits implementation (fallback path)
     */
    @ParameterizedTest(name = "Lucene {0} - Custom Bits Implementation")
    @MethodSource("luceneVersionProvider")
    void testConvertCustomBits(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a custom Bits implementation (not FixedBitSet or SparseFixedBitSet)
        // Set bits at even indices
        Object customBits = testCase.createCustomBits(idx -> idx % 2 == 0, 20);
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(customBits);
        
        // Verify the result
        assertNotNull(result);
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                assertTrue(result.get(i), "Bit at index " + i + " should be set");
            } else {
                assertFalse(result.get(i), "Bit at index " + i + " should not be set");
            }
        }
        
        // Verify cardinality (10 even indices from 0 to 18)
        assertEquals(10, result.cardinality(), "Should have 10 bits set (even indices)");
    }

    /**
     * Test empty bitsets
     */
    @ParameterizedTest(name = "Lucene {0} - Empty BitSets")
    @MethodSource("luceneVersionProvider")
    void testConvertEmptyBitSets(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Test with an empty FixedBitSet
        Object emptyFixed = testCase.createFixedBitSet(10);
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(emptyFixed);
        
        assertNotNull(result);
        assertEquals(0, result.cardinality(), "Empty FixedBitSet should result in empty BitSet");
        
        // Test with an empty SparseFixedBitSet
        Object emptySparse = testCase.createSparseFixedBitSet(50);
        result = testCase.convertBits(emptySparse);
        
        assertNotNull(result);
        assertEquals(0, result.cardinality(), "Empty SparseFixedBitSet should result in empty BitSet");
    }

    /**
     * Test all bits set
     */
    @ParameterizedTest(name = "Lucene {0} - All Bits Set")
    @MethodSource("luceneVersionProvider")
    void testConvertAllBitsSet(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Test with all bits set in FixedBitSet
        Object allSetFixed = testCase.createFixedBitSet(8);
        for (int i = 0; i < 8; i++) {
            testCase.setBit(allSetFixed, i);
        }
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(allSetFixed);
        
        assertNotNull(result);
        assertEquals(8, result.cardinality(), "All bits should be set");
        for (int i = 0; i < 8; i++) {
            assertTrue(result.get(i), "Bit " + i + " should be set");
        }
    }

    /**
     * Test with null classes (simulating older Lucene versions or fallback behavior)
     */
    @ParameterizedTest(name = "Lucene {0} - Null Classes Fallback")
    @MethodSource("luceneVersionProvider")
    void testConvertWithNullClasses(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a custom Bits with specific bits set
        Object customBits = testCase.createCustomBits(idx -> idx == 1 || idx == 3, 5);
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBitsWithNullClasses(customBits);
        
        // Should fall back to manual iteration
        assertNotNull(result);
        assertFalse(result.get(0));
        assertTrue(result.get(1));
        assertFalse(result.get(2));
        assertTrue(result.get(3));
        assertFalse(result.get(4));
    }

    /**
     * Parameterized test that verifies BitSetConverter works correctly across all Lucene versions
     * with a large dataset (1 million bits with 35% density)
     */
    @ParameterizedTest(name = "Lucene {0} - Large BitSet Conversion")
    @MethodSource("luceneVersionProvider")
    void testLargeBitSetConversionWithRandomSeed(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a FixedBitSet with 1 million bits
        Object fixedBitSet = testCase.createFixedBitSet(LARGE_BIT_SET_SIZE);
        
        // Use a seeded random to set approximately 35% of the bits
        Random random = new Random(RANDOM_SEED);
        Set<Integer> expectedSetBits = new HashSet<>();
        int bitsToSet = (int) (LARGE_BIT_SET_SIZE * BIT_DENSITY);
        
        // Generate random indices to set
        while (expectedSetBits.size() < bitsToSet) {
            int index = random.nextInt(LARGE_BIT_SET_SIZE);
            if (expectedSetBits.add(index)) {
                testCase.setBit(fixedBitSet, index);
            }
        }
        
        // Convert using BitSetConverter
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(fixedBitSet);
        
        // Verify the result
        assertNotNull(result, "Conversion result should not be null for Lucene " + version);
        
        // Verify all expected bits are set
        for (int index : expectedSetBits) {
            assertTrue(result.get(index), 
                String.format("Bit at index %d should be set in Lucene %s", index, version));
        }
        
        // Verify no unexpected bits are set
        int actualSetBits = 0;
        for (int i = 0; i < LARGE_BIT_SET_SIZE; i++) {
            if (result.get(i)) {
                actualSetBits++;
                assertTrue(expectedSetBits.contains(i),
                    String.format("Unexpected bit set at index %d in Lucene %s", i, version));
            }
        }
        
        // Verify the total count matches
        assertEquals(expectedSetBits.size(), actualSetBits,
            String.format("Total number of set bits should match for Lucene %s", version));
    }

    /**
     * Parameterized test for SparseFixedBitSet conversion
     */
    @ParameterizedTest(name = "Lucene {0} - Sparse BitSet Conversion")
    @MethodSource("luceneVersionProvider")
    void testSparseBitSetConversion(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Create a SparseFixedBitSet with sparse data (only 0.1% density)
        int size = 100_000;
        Object sparseBitSet = testCase.createSparseFixedBitSet(size);
        
        // Set only a few bits (0.1% density = 100 bits)
        Random random = new Random(RANDOM_SEED);
        Set<Integer> expectedSetBits = new HashSet<>();
        while (expectedSetBits.size() < 100) {
            int index = random.nextInt(size);
            if (expectedSetBits.add(index)) {
                testCase.setBit(sparseBitSet, index);
            }
        }
        
        // Convert using BitSetConverter
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(sparseBitSet);
        
        // Verify the result
        assertNotNull(result, "Conversion result should not be null for Lucene " + version);
        
        // Verify all expected bits are set and count total
        int actualSetBits = 0;
        for (int i = 0; i < size; i++) {
            if (result.get(i)) {
                actualSetBits++;
                assertTrue(expectedSetBits.contains(i),
                    String.format("Unexpected bit set at index %d in sparse Lucene %s", i, version));
            }
        }
        
        assertEquals(expectedSetBits.size(), actualSetBits,
            String.format("Sparse conversion should preserve all bits for Lucene %s", version));
    }

    /**
     * Parameterized test for edge cases
     */
    @ParameterizedTest(name = "Lucene {0} - Edge Cases")
    @MethodSource("luceneVersionProvider")
    void testEdgeCases(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Test 1: Empty FixedBitSet
        Object emptyFixed = testCase.createFixedBitSet(1000);
        BitSetConverter.FixedLengthBitSet emptyResult = testCase.convertBits(emptyFixed);
        
        assertNotNull(emptyResult);
        assertEquals(0, emptyResult.cardinality(), 
            "Empty bitset should have cardinality 0 for Lucene " + version);
        
        // Test 2: All bits set
        Object allSetFixed = testCase.createFixedBitSet(100);
        for (int i = 0; i < 100; i++) {
            testCase.setBit(allSetFixed, i);
        }
        
        BitSetConverter.FixedLengthBitSet allSetResult = testCase.convertBits(allSetFixed);
        
        assertNotNull(allSetResult);
        assertEquals(100, allSetResult.cardinality(),
            "All bits should be set for Lucene " + version);

        // Test 3: Single bit set
        Object singleBitFixed = testCase.createFixedBitSet(1000);
        testCase.setBit(singleBitFixed, 500);
        
        BitSetConverter.FixedLengthBitSet singleBitResult = testCase.convertBits(singleBitFixed);
        
        assertNotNull(singleBitResult);
        assertEquals(1, singleBitResult.cardinality(),
            "Single bit should be set for Lucene " + version);
        assertTrue(singleBitResult.get(500),
            "Bit at index 500 should be set for Lucene " + version);
    }

    /**
     * Test alternating bit patterns
     */
    @ParameterizedTest(name = "Lucene {0} - Alternating Patterns")
    @MethodSource("luceneVersionProvider")
    void testAlternatingPatterns(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Test with alternating bits in FixedBitSet
        Object alternatingFixed = testCase.createFixedBitSet(100);
        for (int i = 0; i < 100; i += 2) {
            testCase.setBit(alternatingFixed, i);
        }
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(alternatingFixed);
        
        assertNotNull(result);
        assertEquals(50, result.cardinality(), "Half the bits should be set");
        
        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                assertTrue(result.get(i), "Even index " + i + " should be set");
            } else {
                assertFalse(result.get(i), "Odd index " + i + " should not be set");
            }
        }
    }

    /**
     * Test boundary conditions
     */
    @ParameterizedTest(name = "Lucene {0} - Boundary Conditions")
    @MethodSource("luceneVersionProvider")
    void testBoundaryConditions(String version) throws Exception {
        LuceneVersionTestCase testCase = new LuceneVersionTestCase(version);
        
        // Test with bits set at boundaries
        Object boundaryBits = testCase.createFixedBitSet(64);
        testCase.setBit(boundaryBits, 0);   // First bit
        testCase.setBit(boundaryBits, 31);  // Last bit of first int
        testCase.setBit(boundaryBits, 32);  // First bit of second int
        testCase.setBit(boundaryBits, 63);  // Last bit
        
        BitSetConverter.FixedLengthBitSet result = testCase.convertBits(boundaryBits);
        
        assertNotNull(result);
        assertEquals(4, result.cardinality(), "Four boundary bits should be set");
        assertTrue(result.get(0), "First bit should be set");
        assertTrue(result.get(31), "Bit 31 should be set");
        assertTrue(result.get(32), "Bit 32 should be set");
        assertTrue(result.get(63), "Last bit should be set");
        
        // Verify no other bits are set
        for (int i = 1; i < 31; i++) {
            assertFalse(result.get(i), "Bit " + i + " should not be set");
        }
        for (int i = 33; i < 63; i++) {
            assertFalse(result.get(i), "Bit " + i + " should not be set");
        }
    }
}

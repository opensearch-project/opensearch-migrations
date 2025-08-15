package org.opensearch.migrations.bulkload.lucene;

import java.util.BitSet;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Functional converter for converting Lucene Bits implementations to Java BitSet.
 * This converter is designed to work with different Lucene versions without reflection.
 */
public class LiveDocsConverter {
    
    @FunctionalInterface
    public interface BitsAccessor {
        boolean get(int index);
    }
    
    @FunctionalInterface
    public interface NextSetBitFunction {
        int nextSetBit(int fromIndex);
    }
    
    /**
     * Generic converter that works with any Bits implementation.
     * Performs the conversion once and returns a Java BitSet.
     * 
     * @param liveDocs The Lucene Bits object (can be null)
     * @param fixedBitSetClass The FixedBitSet class for this Lucene version
     * @param sparseFixedBitSetClass The SparseFixedBitSet class (null for older versions)
     * @param getBits Function to extract long[] from FixedBitSet
     * @param length Function to get length from Bits
     * @param get Function to get bit at index from Bits
     * @param nextSetBit Function to get next set bit (for sparse bitsets, can be null)
     * @return A Java BitSet containing the live docs, or null if input is null
     */
    public static BitSet convertLiveDocs(
            Object liveDocs,
            Class<?> fixedBitSetClass,
            Class<?> sparseFixedBitSetClass,
            Function<Object, long[]> getBits,
            ToIntFunction<Object> length,
            BitsAccessor get,
            NextSetBitFunction nextSetBit) {
        
        if (liveDocs == null) {
            return null;
        }
        
        // Fast path for FixedBitSet - use efficient array conversion
        if (fixedBitSetClass != null && fixedBitSetClass.isInstance(liveDocs)) {
            return BitSet.valueOf(getBits.apply(liveDocs));
        }
        
        // Fast path for SparseFixedBitSet (Lucene 9) - use iterator for efficiency
        if (sparseFixedBitSetClass != null && sparseFixedBitSetClass.isInstance(liveDocs) && nextSetBit != null) {
            int len = length.applyAsInt(liveDocs);
            BitSet bitSet = new BitSet(len);
            for (int i = nextSetBit.nextSetBit(0); 
                 i != -1 && i + 1 < len;
                 i = nextSetBit.nextSetBit(i + 1)) {
                bitSet.set(i);
            }
            return bitSet;
        }
        
        // Fallback for other Bits implementations - manual iteration
        int len = length.applyAsInt(liveDocs);
        BitSet bitSet = new BitSet(len);
        for (int i = 0; i < len; i++) {
            if (get.get(i)) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }
}

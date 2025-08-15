package org.opensearch.migrations.bulkload.lucene;

import java.util.BitSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Generic converter for converting Lucene Bits implementations to Java BitSet.
 * This converter is designed to work with different Lucene versions without reflection.
 * 
 * @param <B> The Bits type for the specific Lucene version
 * @param <F> The FixedBitSet type for the specific Lucene version
 * @param <S> The SparseFixedBitSet type for the specific Lucene version
 */
public class LiveDocsConverter<B, F extends B, S extends B> {
    
    private final Class<F> fixedBitSetClass;
    private final Class<S> sparseFixedBitSetClass;
    private final Function<F, long[]> getBitsFromFixed;
    private final ToIntFunction<B> getLength;
    private final Predicate<Integer> getBitAt;
    private final Function<S, ToIntFunction<Integer>> nextSetBitFactory;
    
    /**
     * Creates a converter for a specific Lucene version.
     * 
     * @param fixedBitSetClass The FixedBitSet class for this Lucene version
     * @param sparseFixedBitSetClass The SparseFixedBitSet class (null for older versions)
     * @param getBitsFromFixed Function to extract long[] from FixedBitSet
     * @param getLength Function to get length from Bits
     * @param getBitAt Predicate to check if bit at index is set
     * @param nextSetBitFactory Function to create a nextSetBit function for SparseFixedBitSet
     */
    public LiveDocsConverter(
            Class<F> fixedBitSetClass,
            Class<S> sparseFixedBitSetClass,
            Function<F, long[]> getBitsFromFixed,
            ToIntFunction<B> getLength,
            Predicate<Integer> getBitAt,
            Function<S, ToIntFunction<Integer>> nextSetBitFactory) {
        
        this.fixedBitSetClass = fixedBitSetClass;
        this.sparseFixedBitSetClass = sparseFixedBitSetClass;
        this.getBitsFromFixed = getBitsFromFixed;
        this.getLength = getLength;
        this.getBitAt = getBitAt;
        this.nextSetBitFactory = nextSetBitFactory;
    }
    
    /**
     * Converts Lucene Bits to Java BitSet.
     * 
     * @param liveDocs The Lucene Bits object (can be null)
     * @return A Java BitSet containing the live docs, or null if input is null
     */
    @SuppressWarnings("unchecked")
    public BitSet convert(B liveDocs) {
        if (liveDocs == null) {
            return null;
        }
        
        // Fast path for FixedBitSet - use efficient array conversion
        if (fixedBitSetClass != null && fixedBitSetClass.isInstance(liveDocs)) {
            return BitSet.valueOf(getBitsFromFixed.apply((F) liveDocs));
        }
        
        // Fast path for SparseFixedBitSet - use iterator for efficiency
        if (sparseFixedBitSetClass != null && sparseFixedBitSetClass.isInstance(liveDocs) && nextSetBitFactory != null) {
            int len = getLength.applyAsInt(liveDocs);
            BitSet bitSet = new BitSet(len);
            S sparseBits = (S) liveDocs;
            ToIntFunction<Integer> nextSetBit = nextSetBitFactory.apply(sparseBits);
            for (int i = nextSetBit.applyAsInt(0); 
                 i != -1 && i < len;
                 i = nextSetBit.applyAsInt(i + 1)) {
                bitSet.set(i);
            }
            return bitSet;
        }
        
        // Fallback for other Bits implementations - manual iteration
        int len = getLength.applyAsInt(liveDocs);
        BitSet bitSet = new BitSet(len);
        for (int i = 0; i < len; i++) {
            if (getBitAt.test(i)) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }
    
    /**
     * Static method to convert Lucene Bits to Java BitSet.
     * This method creates a converter instance and performs the conversion in one call.
     * 
     * @param <B> The Bits type for the specific Lucene version
     * @param <F> The FixedBitSet type for the specific Lucene version
     * @param <S> The SparseFixedBitSet type for the specific Lucene version
     * @param liveDocs The Lucene Bits object (can be null)
     * @param fixedBitSetClass The FixedBitSet class for this Lucene version
     * @param sparseFixedBitSetClass The SparseFixedBitSet class (null for older versions)
     * @param getBitsFromFixed Function to extract long[] from FixedBitSet
     * @param getLength Function to get length from Bits
     * @param getBitAt Predicate to check if bit at index is set
     * @param nextSetBitFactory Function to create a nextSetBit function for SparseFixedBitSet
     * @return A Java BitSet containing the live docs, or null if input is null
     */
    public static <B, F extends B, S extends B> BitSet convert(
            B liveDocs,
            Class<F> fixedBitSetClass,
            Class<S> sparseFixedBitSetClass,
            Function<F, long[]> getBitsFromFixed,
            ToIntFunction<B> getLength,
            Predicate<Integer> getBitAt,
            Function<S, ToIntFunction<Integer>> nextSetBitFactory) {
        
        LiveDocsConverter<B, F, S> converter = new LiveDocsConverter<>(
            fixedBitSetClass,
            sparseFixedBitSetClass,
            getBitsFromFixed,
            getLength,
            getBitAt,
            nextSetBitFactory
        );
        
        return converter.convert(liveDocs);
    }
}

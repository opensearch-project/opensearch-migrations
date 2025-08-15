package org.opensearch.migrations.bulkload.lucene;

import java.util.BitSet;

import org.junit.jupiter.api.Test;
import shadow.lucene9.org.apache.lucene.util.Bits;
import shadow.lucene9.org.apache.lucene.util.FixedBitSet;
import shadow.lucene9.org.apache.lucene.util.SparseFixedBitSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LiveDocsConverterTest {

    @Test
    public void testConvertNull() {
        // Test that null input returns null
        BitSet result = LiveDocsConverter.convert(
            null,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            idx -> false,
            sparseBits -> sparseBits::nextSetBit
        );
        
        assertNull(result, "Converting null should return null");
    }

    @Test
    public void testConvertFixedBitSet() {
        // Create a FixedBitSet with some bits set
        FixedBitSet fixedBitSet = new FixedBitSet(10);
        fixedBitSet.set(0);
        fixedBitSet.set(3);
        fixedBitSet.set(7);
        fixedBitSet.set(9);
        
        // Spy on the FixedBitSet to verify method calls
        FixedBitSet spyFixed = spy(fixedBitSet);
        
        BitSet result = LiveDocsConverter.convert(
            spyFixed,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            spyFixed::get,
            sparseBits -> sparseBits::nextSetBit
        );
        
        // Verify the result
        assertNotNull(result);
        assertEquals(10, result.length());
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
        
        // Verify that getBits() was called (fast path for FixedBitSet)
        verify(spyFixed, times(1)).getBits();
        // Verify that get() was never called (because we used the fast path)
        verify(spyFixed, never()).get(anyInt());
    }

    @Test
    public void testConvertSparseFixedBitSet() {
        // Create a SparseFixedBitSet with some bits set
        SparseFixedBitSet sparseBitSet = new SparseFixedBitSet(100);
        sparseBitSet.set(5);
        sparseBitSet.set(25);
        sparseBitSet.set(50);
        sparseBitSet.set(99);
        
        // Spy on the SparseFixedBitSet
        SparseFixedBitSet spySparse = spy(sparseBitSet);
        
        BitSet result = LiveDocsConverter.convert(
            spySparse,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            spySparse::get,
            sparseBits -> idx -> {
                if (idx >= sparseBits.length()) {
                    return -1;
                }
                return sparseBits.nextSetBit(idx);
            }
        );
        
        // Verify the result
        assertNotNull(result);
        assertEquals(100, result.length());
        assertFalse(result.get(0));
        assertTrue(result.get(5));
        assertTrue(result.get(25));
        assertTrue(result.get(50));
        assertTrue(result.get(99));
        assertFalse(result.get(98));
        
        // Verify that nextSetBit was called (fast path for SparseFixedBitSet)
        verify(spySparse, atLeastOnce()).nextSetBit(anyInt());
        verify(spySparse, atLeastOnce()).length();
    }

    @Test
    public void testConvertRegularBits() {
        // Create a custom Bits implementation (not FixedBitSet or SparseFixedBitSet)
        Bits customBits = new Bits() {
            @Override
            public boolean get(int index) {
                // Set bits at even indices
                return index % 2 == 0;
            }
            
            @Override
            public int length() {
                return 20;
            }
        };
        
        // Spy on the custom Bits
        Bits spyBits = spy(customBits);
        
        BitSet result = LiveDocsConverter.convert(
            spyBits,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            spyBits::get,
            sparseBits -> sparseBits::nextSetBit
        );
        
        // Verify the result
        assertNotNull(result);
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                assertTrue(result.get(i), "Bit at index " + i + " should be set");
            } else {
                assertFalse(result.get(i), "Bit at index " + i + " should not be set");
            }
        }
        assertEquals(20, result.length());
        
        // Verify that length() and get() were called (fallback path)
        verify(spyBits, times(1)).length();
        verify(spyBits, times(20)).get(anyInt());
    }

    @Test
    public void testConvertWithEmptyBits() {
        // Test with an empty FixedBitSet
        FixedBitSet emptyFixed = new FixedBitSet(10);
        
        BitSet result = LiveDocsConverter.convert(
            emptyFixed,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            emptyFixed::get,
            sparseBits -> sparseBits::nextSetBit
        );
        
        assertNotNull(result);
        assertEquals(0, result.cardinality(), "Empty bits should result in empty BitSet");
        
        // Test with an empty SparseFixedBitSet
        SparseFixedBitSet emptySparse = new SparseFixedBitSet(50);
        
        result = LiveDocsConverter.convert(
            emptySparse,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            emptySparse::get,
            sparseBits -> sparseBits::nextSetBit
        );
        
        assertNotNull(result);
        assertEquals(10, result.length());
        assertEquals(0, result.cardinality(), "Empty sparse bits should result in empty BitSet");
    }

    @Test
    public void testConvertWithAllBitsSet() {
        // Test with all bits set in FixedBitSet
        FixedBitSet allSetFixed = new FixedBitSet(8);
        for (int i = 0; i < 8; i++) {
            allSetFixed.set(i);
        }
        
        BitSet result = LiveDocsConverter.convert(
            allSetFixed,
            FixedBitSet.class,
            SparseFixedBitSet.class,
            FixedBitSet::getBits,
            Bits::length,
            allSetFixed::get,
            sparseBits -> sparseBits::nextSetBit
        );
        
        assertNotNull(result);
        assertEquals(8, result.length());
        assertEquals(8, result.cardinality(), "All bits should be set");
        for (int i = 0; i < 8; i++) {
            assertTrue(result.get(i), "Bit " + i + " should be set");
        }
    }

    @Test
    public void testConvertWithNullClasses() {
        // Test with null class parameters (simulating possible older Lucene versions without SparseFixedBitSet)
        Bits customBits = new Bits() {
            @Override
            public boolean get(int index) {
                return index == 1 || index == 3;
            }
            
            @Override
            public int length() {
                return 5;
            }
        };
        
        BitSet result = LiveDocsConverter.convert(
            customBits,
            null,  // No FixedBitSet class
            null,  // No SparseFixedBitSet class
            obj -> new long[0],  // Won't be used
            Bits::length,
            customBits::get,
            null  // No nextSetBit function
        );
        
        // Should fall back to manual iteration
        assertNotNull(result);
        assertEquals(5, result.length());
        assertFalse(result.get(0));
        assertTrue(result.get(1));
        assertFalse(result.get(2));
        assertTrue(result.get(3));
        assertFalse(result.get(4));
    }
}

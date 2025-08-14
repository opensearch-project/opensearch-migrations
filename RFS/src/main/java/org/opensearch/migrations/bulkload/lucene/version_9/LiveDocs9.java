package org.opensearch.migrations.bulkload.lucene.version_9;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;

import lombok.RequiredArgsConstructor;
import shadow.lucene9.org.apache.lucene.util.Bits;
import shadow.lucene9.org.apache.lucene.util.FixedBitSet;

@RequiredArgsConstructor
public class LiveDocs9 implements LuceneLiveDocs {

    private final Bits wrapped;

    private volatile FixedBitSet cachedFixedBits = null;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }

    public long length() {
        return wrapped.length();
    }

    public LuceneLiveDocs xor(LuceneLiveDocs other) {
        return applyMutatingOp(other, "xor", FixedBitSet::xor);
    }

    public LuceneLiveDocs and(LuceneLiveDocs other) {
        return applyMutatingOp(other, "and", FixedBitSet::and);
    }

    public LuceneLiveDocs or(LuceneLiveDocs other) {
        return applyMutatingOp(other, "or", FixedBitSet::or);
    }

    public long andNotCount(LuceneLiveDocs other) {
        return applyNonMutatingOp(other, "andNotCount", FixedBitSet::andNotCount);
    }

    public LuceneLiveDocs andNot(LuceneLiveDocs other) {
        return applyMutatingOp(other, "andNot", FixedBitSet::andNot);
    }

    public LuceneLiveDocs not() {
        var clone = fixedBitSet().clone();
        clone.flip(0, wrapped.length());
        return new LiveDocs9(clone);
    }

    public long cardinality() {
        return fixedBitSet().cardinality();
    }

    public List<Integer> getAllEnabledDocIdxs() {
        var fixedBits = fixedBitSet();
        List<Integer> enabledIdxs = new ArrayList<>();
        for (int idx = fixedBits.nextSetBit(0); idx != -1; idx = fixedBits.nextSetBit(idx + 1)) {
            enabledIdxs.add(idx);
        }
        return enabledIdxs;
    }

    private LuceneLiveDocs applyMutatingOp(LuceneLiveDocs other,
                                           String opName,
                                           java.util.function.BiConsumer<FixedBitSet, FixedBitSet> op) {
        if (!(other instanceof LiveDocs9 o)) {
            throw new UnsupportedOperationException(opName + " only supported when other is a LiveDocs9");
        }
        FixedBitSet clone = fixedBitSet().clone();
        op.accept(clone, o.fixedBitSet());
        return new LiveDocs9(clone);
    }

    private <T> T applyNonMutatingOp(LuceneLiveDocs other,
                               String opName,
                               java.util.function.BiFunction<FixedBitSet, FixedBitSet, T> op) {
        if (!(other instanceof LiveDocs9 o)) {
            throw new UnsupportedOperationException(opName + " only supported when other is a LiveDocs9");
        }
        return op.apply(fixedBitSet(), o.fixedBitSet());
    }

    private FixedBitSet fixedBitSet() {
        if (cachedFixedBits == null) {
            synchronized (this) {
                if (wrapped instanceof FixedBitSet) {
                    cachedFixedBits = (FixedBitSet) wrapped;
                } else {
                    int length = wrapped.length();
                    FixedBitSet fixedBits = new FixedBitSet(length);
                    for (int i = 0; i < length; i++) {
                        if (wrapped.get(i)) {
                            fixedBits.set(i);
                        }
                    }
                    cachedFixedBits = fixedBits;
                }
            }
        }
        return cachedFixedBits;
    }
}

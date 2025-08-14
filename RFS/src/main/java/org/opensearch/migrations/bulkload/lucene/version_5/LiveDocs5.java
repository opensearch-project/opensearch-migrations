package org.opensearch.migrations.bulkload.lucene.version_5;

import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;

import lombok.RequiredArgsConstructor;
import shadow.lucene5.org.apache.lucene.util.Bits;
import shadow.lucene5.org.apache.lucene.util.FixedBitSet;

@RequiredArgsConstructor
public class LiveDocs5 implements LuceneLiveDocs {

    private final Bits wrapped;

    private volatile FixedBitSet cachedFixedBits = null;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }

    public long length() {
        return wrapped.length();
    }

    public LuceneLiveDocs xor(LuceneLiveDocs other) {
        return applyOp(other, "xor", FixedBitSet::xor);
    }

    public LuceneLiveDocs and(LuceneLiveDocs other) {
        return applyOp(other, "and", FixedBitSet::and);
    }

    public LuceneLiveDocs or(LuceneLiveDocs other) {
        return applyOp(other, "or", FixedBitSet::or);
    }

    public LuceneLiveDocs andNot(LuceneLiveDocs other) {
        return applyOp(other, "andNot", FixedBitSet::andNot);
    }

    public LuceneLiveDocs not() {
        var clone = fixedBitSet().clone();
        clone.flip(0, wrapped.length());
        return new LiveDocs5(clone);
    }

    private LuceneLiveDocs applyOp(LuceneLiveDocs other,
                              String opName,
                              java.util.function.BiConsumer<FixedBitSet, FixedBitSet> op) {
        if (!(other instanceof LiveDocs5 o)) {
            throw new UnsupportedOperationException(opName + " only supported when other is a LiveDocs5");
        }
        FixedBitSet clone = fixedBitSet().clone();
        op.accept(clone, o.fixedBitSet());
        return new LiveDocs5(clone);
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

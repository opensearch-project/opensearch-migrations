package org.opensearch.migrations;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import shadow.lucene9.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.codecs.PostingsReaderBase;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.blocktree.Lucene90BlockTreeTermsReader;
import shadow.lucene9.org.apache.lucene.codecs.lucene912.Lucene912PostingsReader;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;
import shadow.lucene9.org.apache.lucene.index.Terms;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.util.IOUtils;

public class ES812Postings extends PostingsFormat {

    public ES812Postings() {
        super("ES812Postings");
    }

    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES812Postings is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        Directory dir = state.directory;
        String[] files = dir.listAll();

        boolean hasMeta = Arrays.stream(files)
                .anyMatch(f -> f.endsWith(".psm") || (f.contains("_ES812Postings_") && f.endsWith(".psm")));

        if (hasMeta) {
            // Try using Lucene912PostingsReader as normal
            PostingsReaderBase postingsReader = new Lucene912PostingsReader(state);
            boolean success = false;
            try {
                FieldsProducer ret = new Lucene90BlockTreeTermsReader(postingsReader, state);
                success = true;
                return ret;
            } finally {
                if (!success) {
                    IOUtils.closeWhileHandlingException(postingsReader);
                }
            }
        } else {
            // Fallback to dummy reader if .psm is missing
            return new FieldsProducer() {
                @Override
                public void close() {}

                @Override
                public void checkIntegrity() {}

                @Override
                public Iterator<String> iterator() {
                    return java.util.Collections.emptyIterator();
                }

                @Override
                public Terms terms(String field) {
                    return null;
                }

                @Override
                public int size() {
                    return 0;
                }
            };
        }
    }
}

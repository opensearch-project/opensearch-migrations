package org.opensearch.migrations;

import shadow.lucene9.org.apache.lucene.codecs.*;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.Lucene90CompoundFormat;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.blocktree.Lucene90BlockTreeTermsReader;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.blocktree.Lucene90BlockTreeTermsWriter;
import shadow.lucene9.org.apache.lucene.codecs.lucene912.Lucene912PostingsFormat;
import shadow.lucene9.org.apache.lucene.codecs.lucene912.Lucene912PostingsReader;
import shadow.lucene9.org.apache.lucene.codecs.lucene912.Lucene912PostingsWriter;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;
import shadow.lucene9.org.apache.lucene.index.TermState;
import shadow.lucene9.org.apache.lucene.util.IOUtils;

import java.io.Closeable;
import java.io.IOException;

public class ES812Postings extends PostingsFormat {

    public static final String META_EXTENSION = "psm";
public static final String DOC_EXTENSION = "doc";
public static final String POS_EXTENSION = "pos";
public static final String PAY_EXTENSION = "pay";
public static final int BLOCK_SIZE = 128;
public static final int BLOCK_MASK = 127;
public static final int LEVEL1_FACTOR = 32;
public static final int LEVEL1_NUM_DOCS = 4096;
public static final int LEVEL1_MASK = 4095;
static final String TERMS_CODEC = "Lucene90PostingsWriterTerms";
static final String META_CODEC = "Lucene912PostingsWriterMeta";
static final String DOC_CODEC = "Lucene912PostingsWriterDoc";
static final String POS_CODEC = "Lucene912PostingsWriterPos";
static final String PAY_CODEC = "Lucene912PostingsWriterPay";
static final int VERSION_START = 0;
static final int VERSION_CURRENT = 0;
private final int minTermBlockSize;
private final int maxTermBlockSize;

public ES812Postings() {
    this(25, 48);
}

public ES812Postings(int minTermBlockSize, int maxTermBlockSize) {
    super("ES812Postings");
    Lucene90BlockTreeTermsWriter.validateSettings(minTermBlockSize, maxTermBlockSize);
    this.minTermBlockSize = minTermBlockSize;
    this.maxTermBlockSize = maxTermBlockSize;
}

public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    PostingsWriterBase postingsWriter = new Lucene912PostingsWriter(state);
    boolean success = false;

    Object var5;
    try {
        FieldsConsumer ret = new Lucene90BlockTreeTermsWriter(state, postingsWriter, this.minTermBlockSize, this.maxTermBlockSize);
        success = true;
        var5 = ret;
    } finally {
        if (!success) {
            IOUtils.closeWhileHandlingException(new Closeable[]{postingsWriter});
        }

    }

    return (FieldsConsumer)var5;
}

public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    PostingsReaderBase postingsReader = new Lucene912PostingsReader(state);
    boolean success = false;

    Object var5;
    try {
        FieldsProducer ret = new Lucene90BlockTreeTermsReader(postingsReader, state);
        success = true;
        var5 = ret;
    } finally {
        if (!success) {
            IOUtils.closeWhileHandlingException(new Closeable[]{postingsReader});
        }

    }

    return (FieldsProducer)var5;
}

public static final class IntBlockTermState extends BlockTermState {
    public long docStartFP;
    public long posStartFP;
    public long payStartFP;
    public long lastPosBlockOffset = -1L;
    public int singletonDocID = -1;

    public Lucene912PostingsFormat.IntBlockTermState clone() {
        Lucene912PostingsFormat.IntBlockTermState other = new Lucene912PostingsFormat.IntBlockTermState();
        other.copyFrom(this);
        return other;
    }

    public void copyFrom(TermState _other) {
        super.copyFrom(_other);
        Lucene912PostingsFormat.IntBlockTermState other = (Lucene912PostingsFormat.IntBlockTermState)_other;
        this.docStartFP = other.docStartFP;
        this.posStartFP = other.posStartFP;
        this.payStartFP = other.payStartFP;
        this.lastPosBlockOffset = other.lastPosBlockOffset;
        this.singletonDocID = other.singletonDocID;
    }

    public String toString() {
        String var10000 = super.toString();
        return var10000 + " docStartFP=" + this.docStartFP + " posStartFP=" + this.posStartFP + " payStartFP=" + this.payStartFP + " lastPosBlockOffset=" + this.lastPosBlockOffset + " singletonDocID=" + this.singletonDocID;
    }
}

}

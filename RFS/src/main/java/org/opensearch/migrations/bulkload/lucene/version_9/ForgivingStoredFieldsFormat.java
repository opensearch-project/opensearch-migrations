package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsReader;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsWriter;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.Lucene90StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.Lucene90StoredFieldsFormat.Mode;
import shadow.lucene9.org.apache.lucene.index.FieldInfos;
import shadow.lucene9.org.apache.lucene.index.SegmentInfo;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.IOContext;

public class ForgivingStoredFieldsFormat extends StoredFieldsFormat {

    @Override
    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context)
            throws IOException {
        if (si.getAttribute("Lucene90StoredFieldsFormat.mode") == null) {
            System.out.println(">>>>> Injecting missing mode BEST_SPEED into segment info");
            si.putAttribute("Lucene90StoredFieldsFormat.mode", "BEST_SPEED");
        }
        return new Lucene90StoredFieldsFormat(Mode.BEST_SPEED).fieldsReader(directory, si, fn, context);
    }

    @Override
    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context)
            throws IOException {
        return new Lucene90StoredFieldsFormat(Mode.BEST_SPEED).fieldsWriter(directory, si, context);
    }
}

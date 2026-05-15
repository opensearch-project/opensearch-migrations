package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SegmentDocValueReader implements AutoCloseable {

    private final LuceneLeafReader reader;
    private final List<DocValueFieldInfo> dvFields;
    private final Map<String, DocValueFieldInfo> fieldsByName;

    public SegmentDocValueReader(LuceneLeafReader reader) throws IOException {
        this.reader = reader;
        this.dvFields = new ArrayList<>();
        this.fieldsByName = new HashMap<>();
        for (DocValueFieldInfo fi : reader.getDocValueFields()) {
            dvFields.add(fi);
            fieldsByName.put(fi.name(), fi);
        }
        reader.initDocValueIterators(dvFields);
        log.atDebug().setMessage("SegmentDocValueReader initialized with {} doc_value fields")
            .addArgument(dvFields.size()).log();
    }

    public List<DocValueFieldInfo> getDocValueFields() {
        return dvFields;
    }

    public DocValueFieldInfo getFieldByName(String fieldName) {
        return fieldsByName.get(fieldName);
    }

    public Object getDocValue(int docId, DocValueFieldInfo fieldInfo) throws IOException {
        return reader.getDocValue(docId, fieldInfo);
    }

    @Override
    public void close() {
        // Reader lifecycle is managed by the caller; nothing owned to release here.
    }
}

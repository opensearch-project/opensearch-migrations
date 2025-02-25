package org.opensearch.migrations.bulkload.lucene;

import java.util.Comparator;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

/**
 * We need to ensure a stable ordering of segments so we can start reading from a specific segment and document id.
 * To do this, we sort the segments by their ID or name.
 */
@Slf4j
class SegmentNameSorter implements Comparator<MyLeafReader> {

    static final SegmentNameSorter INSTANCE = new SegmentNameSorter();

    @Override
    public int compare(MyLeafReader leafReader1, MyLeafReader leafReader2) {
        var compareResponse = compareIfSegmentReader(leafReader1, leafReader2);
        if (compareResponse == 0) {
            Function<MyLeafReader, String> getLeafReaderDebugInfo = leafReader -> {
                var leafDetails = new StringBuilder();
                leafDetails.append("Class: ").append(leafReader.getClass().getName()).append("\n");
                leafDetails.append("Context: ").append(leafReader.getContextString()).append("\n");
                if (leafReader.getSegmentInfoString() != null) {
                    var segmentInfo = leafReader.getSegmentInfoString();
                    leafDetails.append("SegmentInfo: ").append(segmentInfo).append("\n");
                }
                return leafDetails.toString();
            };
            log.atWarn().setMessage("Unexpected equality during leafReader sorting, expected sort to yield no equality " +
                    "to ensure consistent segment ordering. This may cause missing documents if both segments" +
                    "contains docs. LeafReader1DebugInfo: {} \nLeafReader2DebugInfo: {}")
                    .addArgument(getLeafReaderDebugInfo.apply(leafReader1))
                    .addArgument(getLeafReaderDebugInfo.apply(leafReader2))
                    .log();
            assert false: "Expected unique segmentName sorting for stable sorting.";
        }
        return compareResponse;
    }

    private int compareIfSegmentReader(MyLeafReader leafReader1, MyLeafReader leafReader2) {
        // If both LeafReaders are SegmentReaders, sort on segment info name.
        // Name is the "Unique segment name in the directory" which is always present on a SegmentInfo
        if (leafReader1.getSegmentName() != null && leafReader2.getSegmentName() != null) {
            var segmentName1 = leafReader1.getSegmentName();
            var segmentName2 = leafReader2.getSegmentName();
            return segmentName1.compareTo(segmentName2);
        }
        // Otherwise, keep initial sort
        return 0;
    }
}
package org.opensearch.migrations.bulkload.common.bulk;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class IndexOpTest {
    @Test
    public void testDeleteOp() {
        Assertions.assertEquals(IndexOp.OP_TYPE_VALUE, IndexOp.OP_TYPE.getValue());
    }
}

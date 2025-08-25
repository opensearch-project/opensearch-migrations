package org.opensearch.migrations.bulkload.common.bulk;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class DeleteOpTest {
    @Test
    public void testDeleteOp() {
        Assertions.assertEquals(DeleteOp.OP_TYPE_VALUE, DeleteOp.OP_TYPE.getValue());
    }
}

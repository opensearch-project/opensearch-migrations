package org.opensearch.migrations.bulkload.workcoordination;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseClient extends AutoCloseable {
    <T> T executeInTransaction(TransactionFunction<T> operation) throws SQLException;
    
    @FunctionalInterface
    interface TransactionFunction<T> {
        T apply(Connection conn) throws SQLException;
    }
}

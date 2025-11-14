package org.opensearch.migrations.bulkload.workcoordination;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SqlConfig {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    
    public SqlConfig(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, "work_items");
    }
}

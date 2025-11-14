CREATE TABLE IF NOT EXISTS work_items (
    work_item_id VARCHAR(255) PRIMARY KEY,
    script_version VARCHAR(10) NOT NULL DEFAULT '2.0',
    expiration BIGINT NOT NULL,
    completed_at BIGINT,
    lease_holder_id VARCHAR(255),
    creator_id VARCHAR(255) NOT NULL,
    next_acquisition_lease_exponent INT NOT NULL DEFAULT 0,
    successor_items TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_expiration ON work_items(expiration) WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lease_holder ON work_items(lease_holder_id) WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_completed ON work_items(completed_at) WHERE completed_at IS NOT NULL;

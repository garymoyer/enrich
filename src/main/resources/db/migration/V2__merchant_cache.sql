CREATE TABLE merchant_cache (
    merchant_id   VARCHAR(36)    NOT NULL,
    description   NVARCHAR(500)  NOT NULL,
    merchant_name NVARCHAR(255)  NOT NULL DEFAULT '',
    plaid_response NVARCHAR(MAX),
    created_at    DATETIME2      NOT NULL DEFAULT GETUTCDATE(),
    CONSTRAINT PK_merchant_cache PRIMARY KEY (merchant_id),
    CONSTRAINT UQ_merchant_desc_name UNIQUE (description, merchant_name)
);
CREATE INDEX idx_merchant_cache_lookup ON merchant_cache(description, merchant_name);

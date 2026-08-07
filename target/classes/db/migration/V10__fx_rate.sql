CREATE TABLE fx_rate (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_ccy    CHAR(3)       NOT NULL,
    quote_ccy   CHAR(3)       NOT NULL,
    rate_date   DATE          NOT NULL,
    rate        DECIMAL(19,8) NOT NULL,
    source      VARCHAR(32)   NOT NULL,
    fetched_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_fx UNIQUE (base_ccy, quote_ccy, rate_date),
    INDEX idx_fx_date (rate_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

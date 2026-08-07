CREATE TABLE app_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    google_sub   VARCHAR(255) NOT NULL,
    email        VARCHAR(320) NOT NULL,
    display_name VARCHAR(255),
    picture_url  VARCHAR(1024),
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_app_user_google_sub UNIQUE (google_sub),
    CONSTRAINT uk_app_user_email      UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE instrument (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol     VARCHAR(20)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    asset_type VARCHAR(16)  NOT NULL,
    currency   CHAR(3)      NOT NULL,
    exchange   VARCHAR(32),
    sector     VARCHAR(64),
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_instrument_symbol UNIQUE (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    name          VARCHAR(120) NOT NULL,
    base_currency CHAR(3)      NOT NULL DEFAULT 'USD',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_portfolio_user      FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_portfolio_user_name UNIQUE (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE txn (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id  BIGINT         NOT NULL,
    instrument_id BIGINT         NULL,          -- NULL for DEPOSIT / WITHDRAWAL
    txn_type      VARCHAR(16)    NOT NULL,
    quantity      DECIMAL(19,6)  NOT NULL DEFAULT 0,
    price         DECIMAL(19,4)  NOT NULL DEFAULT 0,
    fees          DECIMAL(19,4)  NOT NULL DEFAULT 0,
    currency      CHAR(3)        NOT NULL,
    executed_at   DATETIME(6)    NOT NULL,
    note          VARCHAR(500),
    created_at    DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_txn_portfolio  FOREIGN KEY (portfolio_id)  REFERENCES portfolio(id),
    CONSTRAINT fk_txn_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT ck_txn_quantity   CHECK (quantity >= 0),
    CONSTRAINT ck_txn_price      CHECK (price >= 0),
    INDEX idx_txn_portfolio_executed (portfolio_id, executed_at),
    INDEX idx_txn_instrument (instrument_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- projection, rebuildable from txn at any time
CREATE TABLE holding (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id  BIGINT        NOT NULL,
    instrument_id BIGINT        NOT NULL,
    quantity      DECIMAL(19,6) NOT NULL,
    avg_cost      DECIMAL(19,4) NOT NULL,
    realised_pnl  DECIMAL(19,4) NOT NULL DEFAULT 0,
    updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                  ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_holding_portfolio  FOREIGN KEY (portfolio_id)  REFERENCES portfolio(id),
    CONSTRAINT fk_holding_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT uk_holding UNIQUE (portfolio_id, instrument_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE price_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_id BIGINT        NOT NULL,
    price_date    DATE          NOT NULL,
    close_price   DECIMAL(19,4) NOT NULL,
    source        VARCHAR(32)   NOT NULL,
    fetched_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_price_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CONSTRAINT uk_price UNIQUE (instrument_id, price_date),
    INDEX idx_price_date (price_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio_valuation_daily (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id   BIGINT        NOT NULL,
    valuation_date DATE          NOT NULL,
    market_value   DECIMAL(19,4) NOT NULL,
    cost_basis     DECIMAL(19,4) NOT NULL,
    cash_balance   DECIMAL(19,4) NOT NULL DEFAULT 0,
    unrealised_pnl DECIMAL(19,4) NOT NULL,
    CONSTRAINT fk_val_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio(id),
    CONSTRAINT uk_val UNIQUE (portfolio_id, valuation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

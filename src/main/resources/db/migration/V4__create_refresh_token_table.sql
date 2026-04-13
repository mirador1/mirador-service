-- Refresh token storage for JWT token rotation.
-- Each refresh token is single-use: consumed on refresh, replaced by a new one.

CREATE TABLE refresh_token (
    id          BIGSERIAL    PRIMARY KEY,
    token       VARCHAR(255) UNIQUE NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    expiry_date TIMESTAMP    NOT NULL
);

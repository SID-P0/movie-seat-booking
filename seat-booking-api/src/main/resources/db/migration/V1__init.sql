-- V1__init.sql
-- Core schema for the seat booking system.
-- Money is integer paise, never floating point.

CREATE TABLE IF NOT EXISTS users (
    user_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       TEXT NOT NULL UNIQUE,
    token_hash  TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS shows (
    show_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    total_seats     INT NOT NULL,
    price     BIGINT NOT NULL,
    per_user_limit  INT NOT NULL DEFAULT 4,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS seats (
    show_id         UUID NOT NULL REFERENCES shows(show_id),
    seat_id         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id  UUID,
    PRIMARY KEY (show_id, seat_id)
);

CREATE INDEX idx_seats_show_status ON seats(show_id, status);

CREATE TABLE IF NOT EXISTS reservations (
    reservation_id  UUID PRIMARY KEY,
    show_id         UUID NOT NULL REFERENCES shows(show_id),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    seats           TEXT[] NOT NULL,
    amount    BIGINT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'confirmed'
                    CHECK (status IN ('held', 'confirmed', 'cancelled', 'expired')),
    idempotency_key TEXT NOT NULL,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_reservations_user_show ON reservations(user_id, show_id);
CREATE INDEX idx_reservations_status ON reservations(status);

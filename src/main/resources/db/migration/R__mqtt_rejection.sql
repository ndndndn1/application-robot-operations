-- Separate durable poison-message disposition: ACK follows this transaction's successful commit.
-- Raw bodies are deliberately absent; publisher retains the source event for authorized repair/replay.
create table if not exists mqtt_rejection (
    rejection_key char(64) primary key,
    topic varchar(256) not null,
    payload_sha256 char(64) not null,
    payload_bytes integer not null check (payload_bytes >= 0),
    qos smallint not null,
    retained boolean not null,
    reason varchar(40) not null,
    event_id varchar(80),
    deliveries bigint not null default 1,
    first_seen timestamptz not null default clock_timestamp(),
    last_seen timestamptz not null default clock_timestamp()
);
create index if not exists mqtt_rejection_time on mqtt_rejection(last_seen desc);

-- Post-trade ledger (S16, ADR 0015). Money is integer paise (bigint), never floating point.
-- Every table is filled from the exchange's event stream; each write is idempotent, because Kafka delivery is
-- at least once.

-- One row per exchange trade. The primary key is what makes applying a trade idempotent: a redelivered trade
-- conflicts here and is skipped, together with everything it would have changed (same transaction).
create table trades (
    trade_id        bigint       not null primary key,
    event_id        bigint       not null unique,
    sim_time        bigint       not null,
    symbol          varchar(16)  not null,
    price           bigint       not null,
    quantity        bigint       not null,
    aggressor       varchar(4)   not null,
    buy_order_id    bigint       not null,
    sell_order_id   bigint       not null,
    buy_account_id  bigint       not null,
    sell_account_id bigint       not null
);

-- Each trade seen from each side: what the account bought or sold, what it paid in charges and what it realised.
create table fills (
    trade_id     bigint      not null,
    side         varchar(4)  not null,
    account_id   bigint      not null,
    order_id     bigint      not null,
    symbol       varchar(16) not null,
    price        bigint      not null,
    quantity     bigint      not null,
    charges      bigint      not null,
    realised_pnl bigint      not null,
    sim_time     bigint      not null,
    event_id     bigint      not null,
    primary key (trade_id, side)
);
create index fills_by_account on fills (account_id, event_id);

-- Open position per account and symbol, by the average-cost method. quantity and cost are signed: a short position
-- has negative quantity and negative cost (what was received for it). cost is the total, not a per-share average,
-- so it stays an exact integer (ADR 0015).
create table positions (
    account_id   bigint      not null,
    symbol       varchar(16) not null,
    quantity     bigint      not null,
    cost         bigint      not null,
    realised_pnl bigint      not null,
    charges      bigint      not null,
    bought       bigint      not null,
    sold         bigint      not null,
    turnover     bigint      not null,
    trades       bigint      not null,
    updated_at   bigint      not null,
    primary key (account_id, symbol)
);

-- Last trade price per symbol: the mark for unrealised P&L.
create table marks (
    symbol   varchar(16) not null primary key,
    price    bigint      not null,
    event_id bigint      not null,
    sim_time bigint      not null
);

-- Order history for the blotter. last_event_id guards against applying an older event over a newer one.
create table orders (
    order_id        bigint      not null primary key,
    account_id      bigint      not null,
    client_order_id varchar(64) not null,
    symbol          varchar(16) not null,
    side            varchar(4)  not null,
    order_type      varchar(8)  not null,
    price           bigint      not null,
    quantity        bigint      not null,
    filled_quantity bigint      not null,
    leaves_quantity bigint      not null,
    status          varchar(16) not null,
    reason          varchar(32),
    created_at      bigint      not null,
    updated_at      bigint      not null,
    last_event_id   bigint      not null
);
create index orders_by_account on orders (account_id, order_id);

-- New-order requests the exchange refused (they never got an order id).
create table rejections (
    event_id        bigint      not null primary key,
    account_id      bigint      not null,
    client_order_id varchar(64) not null,
    symbol          varchar(16) not null,
    reason          varchar(32) not null,
    sim_time        bigint      not null
);
create index rejections_by_account on rejections (account_id, event_id);

-- How far the consumer has got per partition (for lag reporting; correctness does not depend on it).
create table consumer_progress (
    topic_partition integer not null primary key,
    last_event_id   bigint  not null,
    events          bigint  not null
);

-- Readable names for account ids (hashes, ADR 0009), learned when a signed-in caller asks for their own account.
-- Only the leaderboard uses it; an account nobody has looked up shows as its id.
create table account_names (
    account_id bigint      not null primary key,
    username   varchar(64) not null,
    label      varchar(32) not null
);

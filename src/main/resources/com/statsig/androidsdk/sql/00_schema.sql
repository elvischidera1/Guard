-- Statsig client SDK core, written as SQL for SQLite (>= 3.38, JSON functions built in).
--
-- Every platform SDK runs the same statements. The host language only:
--   * opens a database and runs the `schema` block once per connection,
--   * binds parameters and reads result rows,
--   * does I/O the SQL asks for (HTTP, timers, regex matching for str_matches),
--   * turns result rows into its model objects (FeatureGate, DynamicConfig, ...).
--
-- File format (parsed by the host, see SqlScript.kt):
--   * `-- name: <block>` starts a named block; a block runs as one transaction.
--   * A statement ends with `;` at the end of a line. A CREATE TRIGGER ends at a line `END;`.
--   * Parameters are named `:param`. Rows of the block's last statement are its result.
--   * All times are epoch milliseconds from the `clock` view unless passed in.
--
-- Storage layout:
--   main.*  durable state (what the original SDK kept in SharedPreferences/DataStore).
--   temp.*  per-client state (what the original SDK kept in memory). Each client owns a
--           connection, so TEMP tables give every client its own session for free.

-- name: connect
-- Run once per connection, outside any transaction (PRAGMAs run as queries: some return a row).
-- Per-client state is scratch data: keep TEMP tables in memory (the default is a temp file).
PRAGMA temp_store = MEMORY;
-- Durable state is a cache: write-ahead logging without a sync per commit (Android's own setting
-- for WAL databases). A crash can lose the last writes, never corrupt the file.
-- Switching a new (empty) file to WAL writes its first page; that write is not synced (it holds
-- no data yet), which makes the switch ~10x faster. An existing WAL database is left as is.
PRAGMA synchronous = OFF;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- name: schema

-- Durable ------------------------------------------------------------------------------------

-- Small facts that outlive a session, e.g. the device's stable ID.
CREATE TABLE IF NOT EXISTS setting (
  key TEXT PRIMARY KEY,
  value TEXT
);

-- Initialize responses, one per (user, SDK key), stored as applied (so that loading them parses
-- nothing): the response's header fields (values_work.header) here, its entities in
-- cached_entity.
CREATE TABLE IF NOT EXISTS cached_values (
  cache_key TEXT PRIMARY KEY,        -- '<user hash>:<sdk key>'
  user_hash TEXT NOT NULL,
  header TEXT NOT NULL,              -- JSON array, see values_work.header
  received_at INTEGER
);

CREATE TABLE IF NOT EXISTS cached_entity (
  cache_key TEXT NOT NULL,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  body TEXT NOT NULL,                -- as in entity_body
  PRIMARY KEY (cache_key, kind, name)
);

-- Custom cache key (StatsigOptions.customCacheKey) -> cache_key. The 10 most recently used
-- entries are kept; cached_values rows nobody maps to are deleted with them.
CREATE TABLE IF NOT EXISTS cache_key_map (
  scoped_key TEXT PRIMARY KEY,
  cache_key TEXT NOT NULL,
  last_used_at INTEGER NOT NULL
);

-- Values kept for getExperiment/getLayer(keepDeviceValue = true).
-- owner is the cache_key for user-based experiments and '' for device-based ones.
CREATE TABLE IF NOT EXISTS sticky_value (
  owner TEXT NOT NULL,
  name_hash TEXT NOT NULL,
  spec TEXT NOT NULL,                -- the experiment/layer as served, JSON (v1 shape)
  PRIMARY KEY (owner, name_hash)
);

-- overrideGate/overrideConfig/overrideLayer. kind: 'gate' | 'config' | 'layer'.
CREATE TABLE IF NOT EXISTS local_override (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,               -- JSON: true/false or an object
  PRIMARY KEY (kind, name)
);

-- Event batches that could not be delivered, retried on the next start / app focus.
CREATE TABLE IF NOT EXISTS failed_log (
  id INTEGER PRIMARY KEY,
  sdk_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  body TEXT NOT NULL,
  retry_count INTEGER NOT NULL,
  event_count TEXT
);

-- Fallback hosts discovered after the default API domain failed (NetworkFallbackResolver).
CREATE TABLE IF NOT EXISTS fallback_url (
  endpoint TEXT PRIMARY KEY,         -- 'initialize' | 'log_event'
  url TEXT NOT NULL,
  previous TEXT NOT NULL,            -- JSON array of urls tried before
  expires_at INTEGER NOT NULL
);

-- Per client ---------------------------------------------------------------------------------

CREATE TEMP VIEW IF NOT EXISTS clock AS
SELECT CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER) AS now_ms;

-- The current user and the values in use. Exactly one row once session_start has run.
CREATE TEMP TABLE IF NOT EXISTS session (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  sdk_key TEXT NOT NULL,
  user TEXT NOT NULL,                -- evaluation copy of the user, JSON
  logging_user TEXT GENERATED ALWAYS AS (json_remove(user, '$.privateAttributes')) STORED,
  first_user TEXT NOT NULL,          -- user the client started with (diagnostics events)
  user_hash TEXT NOT NULL,
  scoped_key TEXT NOT NULL,
  cache_key TEXT NOT NULL,
  source TEXT NOT NULL,              -- EvalSource name
  received_at INTEGER,
  -- header of the values in use (entity rows hold the rest)
  values_user_hash TEXT NOT NULL DEFAULT '',
  lcut INTEGER NOT NULL DEFAULT 0,
  has_updates INTEGER NOT NULL DEFAULT 0,
  hash_used TEXT,
  derived_fields TEXT,
  full_checksum TEXT,
  param_stores TEXT,
  sdk_flags TEXT,
  sdk_configs TEXT,
  bootstrap_metadata TEXT,
  options TEXT                       -- StatsigOptions logging copy, JSON
);

-- Gates, configs, layers and parameter stores of the values in use, keyed as served (the server
-- usually sends djb2-hashed names). Applying values only stores each entity's JSON (body, in the
-- v1 response shape); the `entity` view computes the fields when read, so values with thousands
-- of entities apply quickly while lookups (whose results the host caches) pay for the few rows
-- they read.
CREATE TEMP TABLE IF NOT EXISTS entity_body (
  kind TEXT NOT NULL,                -- 'gate' | 'config' | 'layer' | 'param_store'
  name TEXT NOT NULL,
  body TEXT NOT NULL,                -- JSON object
  PRIMARY KEY (kind, name)
);

-- Columns holding JSON say so.
CREATE TEMP VIEW IF NOT EXISTS entity AS
SELECT kind, name, body,
  CASE kind                                                -- JSON: true/false for gates, else an object
    WHEN 'gate' THEN CASE WHEN json_extract(body, '$.value') THEN 'true' ELSE 'false' END
    WHEN 'param_store' THEN body
    ELSE body -> '$.value' END AS value,
  json_extract(body, '$.rule_id') AS rule_id,
  json_extract(body, '$.group_name') AS group_name,
  json_extract(body, '$.id_type') AS id_type,
  body -> '$.secondary_exposures' AS secondary_exposures,                          -- JSON array
  body -> '$.undelegated_secondary_exposures' AS undelegated_secondary_exposures,  -- JSON array
  coalesce(json_extract(body, '$.is_user_in_experiment'), 0) AS is_user_in_experiment,
  coalesce(json_extract(body, '$.is_experiment_active'), 0) AS is_experiment_active,
  coalesce(json_extract(body, '$.is_device_based'), 0) AS is_device_based,
  json_extract(body, '$.allocated_experiment_name') AS allocated_experiment_name,
  body -> '$.explicit_parameters' AS explicit_parameters,                          -- JSON array
  json_extract(body, '$.passed') AS passed,                                        -- NULL: not served
  body -> '$.parameter_rule_ids' AS parameter_rule_ids                             -- JSON object
FROM entity_body;

-- This client's copy of the overrides (kept in sync with local_override, which persists them).
CREATE TEMP TABLE IF NOT EXISTS override (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (kind, name)
) WITHOUT ROWID;

-- Values that only live for this client (bootstrap values), by cache key.
CREATE TEMP TABLE IF NOT EXISTS memory_values (
  cache_key TEXT PRIMARY KEY,
  scoped_key TEXT NOT NULL,
  payload TEXT NOT NULL,
  bootstrap_metadata TEXT
);

-- name: reset
-- Run when a client closes and its connection is kept for the next client on the same database:
-- drops the per-client state (TEMP tables) so the connection starts like a new one. Tables
-- that only memoize pure functions (hash_memo, djb2_pow) are kept. Each file clears its own.
DELETE FROM session;
DELETE FROM entity_body;
DELETE FROM override;
DELETE FROM memory_values;

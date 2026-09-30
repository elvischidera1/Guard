-- Events: the queue, exposure de-duplication, diagnostics and undelivered batches
-- (the original StatsigLogger, Diagnostics and the offline part of StatsigNetwork).

-- name: schema

-- At most 1000 queued events: the event blocks drop the oldest.
CREATE TEMP TABLE IF NOT EXISTS event_queue (
  id INTEGER PRIMARY KEY,
  event TEXT                         -- the event as sent to log_event, JSON
);

-- Exposures logged recently (reset when the user changes).
CREATE TEMP TABLE IF NOT EXISTS exposure_seen (
  dedupe_key TEXT PRIMARY KEY,
  logged_at INTEGER NOT NULL
);

-- Exposure calls that will be logged, in call order (see the exposure blocks).
CREATE TEMP TABLE IF NOT EXISTS exposure_call (
  dedupe_key TEXT PRIMARY KEY,
  time INTEGER,
  name TEXT,
  rule_id TEXT,
  reason TEXT,
  value INTEGER,                     -- gates
  rule_passed INTEGER,               -- configs
  parameter TEXT,                    -- layers
  allocated TEXT,                    -- layers
  is_explicit INTEGER,               -- layers
  secondary TEXT,
  undelegated TEXT,                  -- layers
  lcut INTEGER,
  received_at INTEGER,
  manual INTEGER
);

-- checkGate/getConfig/... with exposure logging disabled, counted per name.
CREATE TEMP TABLE IF NOT EXISTS non_exposed (
  name TEXT PRIMARY KEY,
  n INTEGER NOT NULL
);

-- Diagnostics markers per context ('initialize' | 'update_user'), at most 30 each.
CREATE TEMP TABLE IF NOT EXISTS marker (
  id INTEGER PRIMARY KEY,
  context TEXT NOT NULL,
  marker TEXT NOT NULL
);

-- The batch taken by the last take_batch.
CREATE TEMP TABLE IF NOT EXISTS batch (
  max_id INTEGER,
  n INTEGER,
  body TEXT
);



-- Event blocks are @defer: the host may queue calls and run them later, in call order, in one
-- transaction. The first statement runs once per call; the others run once after each run of
-- consecutive calls of the block. Each call also gets :time (when it was made) and, for
-- @coalesce blocks, :repeat (how many identical calls it stands for). They return whether the
-- queue is due for a flush (50 events).
--
-- Events are written as JSON text directly: every piece is either a JSON value made by SQLite
-- or the host (user, metadata, exposures) or a string passed through json_quote. Members that
-- would be null are left out, as the original's Gson did.

-- name: log_event
-- @defer
-- @reads: values
-- @writes: events
-- Custom events. value / metadata / statsig_metadata are JSON (or null).
INSERT INTO event_queue (event)
  SELECT '{"eventName":' || json_quote(:event_name)
    || coalesce(',"value":' || json(:value), '')
    || coalesce(',"metadata":' || json(:metadata), '')
    || ',"user":' || s.logging_user
    || ',"time":' || :time
    || coalesce(',"statsigMetadata":' || json(:statsig_metadata), '') || '}'
  FROM session AS s;
DELETE FROM event_queue WHERE id <= (SELECT max(id) FROM event_queue) - 1000;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- Exposures: the same exposure (dedupe key) is logged at most once per 10 minutes. Each call
-- only checks that and notes the exposure in exposure_call (so a repeated exposure costs one
-- index lookup); the events are then built for all noted calls at once. Hence @quiet: once a
-- call has logged, identical calls change nothing for 10 minutes unless the user (session,
-- exposure_seen: domain `values`) changes.

-- name: log_gate_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- reason is EvalDetails.getDetailedReasonString(); lcut / received_at come from the details.
INSERT OR IGNORE INTO exposure_call
    (dedupe_key, time, name, rule_id, reason, value, secondary, lcut, received_at, manual)
  SELECT k.dedupe_key, :time, :name, :rule_id, :reason, :value, :secondary, :lcut, :received_at,
    :manual
  FROM (SELECT json_array('gate', :name, :rule_id, :reason, :value) AS dedupe_key) AS k
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
INSERT INTO event_queue (event)
  SELECT '{"eventName":"statsig::gate_exposure","user":' || s.logging_user
    || ',"time":' || c.time
    || ',"metadata":{"gate":' || json_quote(c.name)
    || ',"gateValue":' || CASE WHEN c.value THEN '"true"' ELSE '"false"' END
    || ',"ruleID":' || json_quote(c.rule_id)
    || ',"reason":' || json_quote(c.reason)
    || ',"time":' || json_quote(coalesce(CAST(c.received_at AS TEXT), 'null'))
    || ',"lcut":' || json_quote(coalesce(CAST(c.lcut AS TEXT), 'null'))
    || coalesce(',"bootstrapMetadata":' || s.bootstrap_metadata, '')
    || CASE WHEN c.manual THEN ',"isManualExposure":"true"' ELSE '' END
    || '},"secondaryExposures":' || json(coalesce(c.secondary, '[]')) || '}'
  FROM exposure_call AS c, session AS s
  ORDER BY c.rowid;
INSERT OR REPLACE INTO exposure_seen (dedupe_key, logged_at)
  SELECT dedupe_key, time FROM exposure_call;
DELETE FROM exposure_call;
DELETE FROM event_queue WHERE id <= (SELECT max(id) FROM event_queue) - 1000;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: log_config_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- getConfig / getExperiment exposures. rule_passed is null, 0 or 1.
INSERT OR IGNORE INTO exposure_call
    (dedupe_key, time, name, rule_id, reason, rule_passed, secondary, lcut, received_at, manual)
  SELECT k.dedupe_key, :time, :name, :rule_id, :reason, :rule_passed, :secondary, :lcut,
    :received_at, :manual
  FROM (SELECT json_array('config', :name, :rule_id, :reason) AS dedupe_key) AS k
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
INSERT INTO event_queue (event)
  SELECT '{"eventName":"statsig::config_exposure","user":' || s.logging_user
    || ',"time":' || c.time
    || ',"metadata":{"config":' || json_quote(c.name)
    || ',"ruleID":' || json_quote(c.rule_id)
    || ',"reason":' || json_quote(c.reason)
    || ',"time":' || json_quote(coalesce(CAST(c.received_at AS TEXT), 'null'))
    || ',"lcut":' || json_quote(coalesce(CAST(c.lcut AS TEXT), 'null'))
    || coalesce(',"bootstrapMetadata":' || s.bootstrap_metadata, '')
    || CASE c.rule_passed WHEN 1 THEN ',"rulePassed":"true"' WHEN 0 THEN ',"rulePassed":"false"'
         ELSE '' END
    || CASE WHEN c.manual THEN ',"isManualExposure":"true"' ELSE '' END
    || '},"secondaryExposures":' || json(coalesce(c.secondary, '[]')) || '}'
  FROM exposure_call AS c, session AS s
  ORDER BY c.rowid;
INSERT OR REPLACE INTO exposure_seen (dedupe_key, logged_at)
  SELECT dedupe_key, time FROM exposure_call;
DELETE FROM exposure_call;
DELETE FROM event_queue WHERE id <= (SELECT max(id) FROM event_queue) - 1000;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: log_layer_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- A layer parameter was read. Explicit parameters (owned by the allocated experiment) expose the
-- full secondary exposures and the experiment; others only the undelegated exposures.
INSERT OR IGNORE INTO exposure_call
    (dedupe_key, time, name, rule_id, reason, parameter, allocated, is_explicit, secondary,
     undelegated, received_at, manual)
  SELECT k.dedupe_key, :time, :name, :rule_id, :reason, :parameter, k.allocated, k.is_explicit,
    :secondary, :undelegated, :received_at, :manual
  FROM (SELECT x.*,
          json_array('layer', :name, :rule_id, x.allocated, :parameter, x.is_explicit, :reason)
            AS dedupe_key
        FROM (SELECT e.is_explicit,
                CASE WHEN e.is_explicit THEN coalesce(:allocated, '') ELSE '' END AS allocated
              FROM (SELECT EXISTS (SELECT 1 FROM json_each(coalesce(:explicit, '[]'))
                                   WHERE value = :parameter) AS is_explicit) AS e) AS x) AS k
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
INSERT INTO event_queue (event)
  SELECT '{"eventName":"statsig::layer_exposure","user":' || s.logging_user
    || ',"time":' || c.time
    || ',"metadata":{"config":' || json_quote(c.name)
    || ',"ruleID":' || json_quote(c.rule_id)
    || ',"allocatedExperiment":' || json_quote(c.allocated)
    || ',"parameterName":' || json_quote(c.parameter)
    || ',"isExplicitParameter":' || CASE WHEN c.is_explicit THEN '"true"' ELSE '"false"' END
    || ',"reason":' || json_quote(c.reason)
    || ',"time":' || json_quote(coalesce(CAST(c.received_at AS TEXT), 'null'))
    || coalesce(',"bootstrapMetadata":' || s.bootstrap_metadata, '')
    || CASE WHEN c.manual THEN ',"isManualExposure":"true"' ELSE '' END
    || '},"secondaryExposures":' || json(CASE WHEN c.is_explicit
      THEN coalesce(c.secondary, '[]') ELSE coalesce(c.undelegated, '[]') END) || '}'
  FROM exposure_call AS c, session AS s
  ORDER BY c.rowid;
INSERT OR REPLACE INTO exposure_seen (dedupe_key, logged_at)
  SELECT dedupe_key, time FROM exposure_call;
DELETE FROM exposure_call;
DELETE FROM event_queue WHERE id <= (SELECT max(id) FROM event_queue) - 1000;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: count_non_exposed
-- @defer
-- @coalesce
-- @writes: events
-- checkGate/getConfig/... with exposure logging disabled, counted per name.
INSERT INTO non_exposed (name, n) VALUES (:name, :repeat)
  ON CONFLICT (name) DO UPDATE SET n = n + excluded.n;


-- name: take_batch
-- @reads: events
-- @writes: events
-- Flush: queue the non-exposed-checks summary, then (if logging is enabled) move every queued
-- event into one log_event request body. Returns the body and event count, or no row.
INSERT INTO event_queue (event)
  SELECT json_object(
    'eventName', 'statsig::non_exposed_checks',
    -- '' || : the original sends the counts as a JSON-encoded string
    'metadata', json_object('checks', '' || (SELECT json_group_object(name, n) FROM non_exposed)),
    'time', now_ms)
  FROM clock WHERE EXISTS (SELECT 1 FROM non_exposed);
DELETE FROM non_exposed;
DELETE FROM hash_memo WHERE rowid <= (SELECT max(rowid) FROM hash_memo) - 2048;
DELETE FROM batch;
-- Events are stored as JSON text, so the body is concatenated rather than re-parsed.
INSERT INTO batch (max_id, n, body)
  SELECT max(id), count(*),
    '{"events":[' || group_concat(event, ',') || '],"statsigMetadata":' || json(:metadata) || '}'
  FROM (SELECT id, event FROM event_queue ORDER BY id)
  WHERE :logging_enabled AND EXISTS (SELECT 1 FROM event_queue);
DELETE FROM event_queue WHERE id <= (SELECT max_id FROM batch);
SELECT body, n FROM batch;


-- name: mark
-- @writes: events
-- A diagnostics marker. Booleans are 0/1/NULL; :error and :evaluation_details are JSON.
INSERT INTO marker (context, marker)
  SELECT :context, json_patch('{}', json_object(
    'key', :key,
    'action', :action,
    'timestamp', (julianday('now') - 2440587.5) * 86400000.0,
    'step', :step,
    'success', json(CASE :success WHEN 1 THEN 'true' WHEN 0 THEN 'false' END),
    'statusCode', :status_code,
    'attempt', :attempt,
    'sdkRegion', :sdk_region,
    'error', json(:error),
    'hasNetwork', json(CASE :has_network WHEN 1 THEN 'true' WHEN 0 THEN 'false' END),
    'evaluationDetails', json(:evaluation_details)))
  WHERE (SELECT count(*) FROM marker WHERE context = :context) < 30;


-- name: log_diagnostics
-- @reads: events
-- @writes: events
-- Turns the markers of :context into one statsig::diagnostics event and clears them.
INSERT INTO event_queue (event)
  SELECT json_object(
    'eventName', 'statsig::diagnostics',
    'user', json(json_remove(s.first_user, '$.privateAttributes')),
    'time', c.now_ms,
    'metadata', json_object(
      'context', :context,
      'markers', '' || (SELECT json_group_array(json(marker)) FROM (SELECT marker FROM marker WHERE context = :context ORDER BY id)),
      'statsigOptions', CASE WHEN :context = 'initialize' THEN coalesce(s.options, 'null') ELSE 'null' END))
  FROM session AS s, clock AS c
  WHERE EXISTS (SELECT 1 FROM marker WHERE context = :context);
DELETE FROM marker WHERE context = :context;
DELETE FROM event_queue WHERE id <= (SELECT max(id) FROM event_queue) - 1000;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: failed_log_save
-- Keeps an undelivered batch for a later retry: at most 10 per SDK key, none older than 3 days,
-- none retried 3 times.
INSERT INTO failed_log (sdk_key, created_at, body, retry_count, event_count)
  SELECT :sdk_key, :created_at, :body, :retry_count, :event_count WHERE :retry_count < 3;
DELETE FROM failed_log
  WHERE sdk_key = :sdk_key AND (retry_count >= 3 OR created_at <= (SELECT now_ms FROM clock) - 259200000);
DELETE FROM failed_log
  WHERE sdk_key = :sdk_key AND id NOT IN (
    SELECT id FROM failed_log WHERE sdk_key = :sdk_key ORDER BY created_at DESC, id DESC LIMIT 10);


-- name: failed_logs_take
-- Removes and returns the batches to retry, oldest first.
SELECT created_at, body, retry_count, event_count
FROM (
  SELECT * FROM failed_log, clock
  WHERE sdk_key = :sdk_key AND retry_count < 3 AND created_at > now_ms - 259200000
  ORDER BY created_at DESC, id DESC LIMIT 10)
ORDER BY created_at, id;
DELETE FROM failed_log WHERE sdk_key = :sdk_key;

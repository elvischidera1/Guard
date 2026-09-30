-- Events: the queue, exposure de-duplication, diagnostics and undelivered batches
-- (the original StatsigLogger, Diagnostics and the offline part of StatsigNetwork).

-- name: schema

CREATE TEMP TABLE IF NOT EXISTS event_queue (
  id INTEGER PRIMARY KEY,
  dedupe_key TEXT,                   -- set for exposures
  event TEXT NOT NULL                -- the event as sent to log_event, JSON
);

-- Exposures logged recently (reset when the user changes).
CREATE TEMP TABLE IF NOT EXISTS exposure_seen (
  dedupe_key TEXT PRIMARY KEY,
  logged_at INTEGER NOT NULL
);

-- Remembers when each exposure was queued (see the exposure blocks).
CREATE TEMP TRIGGER IF NOT EXISTS event_queue_seen AFTER INSERT ON event_queue
WHEN NEW.dedupe_key IS NOT NULL
BEGIN
  INSERT OR REPLACE INTO exposure_seen (dedupe_key, logged_at)
    VALUES (NEW.dedupe_key, json_extract(NEW.event, '$.time'));
END;

-- At most 1000 queued events; the oldest are dropped.
CREATE TEMP TRIGGER IF NOT EXISTS event_queue_bound AFTER INSERT ON event_queue
BEGIN
  DELETE FROM event_queue WHERE id <= NEW.id - 1000;
END;

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

CREATE TEMP VIEW IF NOT EXISTS logging_user AS
SELECT json_remove(user, '$.privateAttributes') AS user FROM session;


-- Event blocks are @defer: the host may queue calls and run them later, in call order, in one
-- transaction. Each call also gets :time (when it was made) and, for @coalesce blocks, :repeat
-- (how many identical calls it stands for). Statements that return rows (the "is the queue due
-- for a flush" check) run once after the queued calls rather than once per call.

-- name: log_event
-- @defer
-- @reads: values
-- @writes: events
-- Custom events. value / metadata / statsig_metadata are JSON (or null). Null members are
-- dropped, as the original's Gson did.
INSERT INTO event_queue (dedupe_key, event)
  SELECT NULL, json_patch('{}', json_object(
    'eventName', :event_name,
    'value', json(:value),
    'metadata', json(:metadata),
    'user', json(u.user),
    'time', :time,
    'statsigMetadata', json(:statsig_metadata)))
  FROM logging_user AS u;
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- Exposures: the same exposure (dedupe key) is logged at most once per 10 minutes. The check
-- comes first so a repeated exposure costs one index lookup; the event_queue triggers keep
-- exposure_seen up to date. Hence @quiet: once a call has logged, identical calls change
-- nothing for 10 minutes unless the user (session, exposure_seen: domain `values`) changes.

-- name: log_gate_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- reason is EvalDetails.getDetailedReasonString(); lcut / received_at come from the details.
INSERT INTO event_queue (dedupe_key, event)
  WITH k (dedupe_key) AS (SELECT json_array('gate', :name, :rule_id, :reason, :value))
  SELECT k.dedupe_key,
    json_patch('{}', json_object(
      'eventName', 'statsig::gate_exposure',
      'user', json(u.user),
      'time', :time,
      'metadata', json_object(
        'gate', :name,
        'gateValue', CASE WHEN :value THEN 'true' ELSE 'false' END,
        'ruleID', :rule_id,
        'reason', :reason,
        'time', coalesce(CAST(:received_at AS TEXT), 'null'),
        'lcut', coalesce(CAST(:lcut AS TEXT), 'null'),
        'bootstrapMetadata', json(s.bootstrap_metadata),
        'isManualExposure', CASE WHEN :manual THEN 'true' END),
      'secondaryExposures', json(coalesce(:secondary, '[]'))))
  FROM k, session AS s, logging_user AS u
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: log_config_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- getConfig / getExperiment exposures. rule_passed is null, 0 or 1.
INSERT INTO event_queue (dedupe_key, event)
  WITH k (dedupe_key) AS (SELECT json_array('config', :name, :rule_id, :reason))
  SELECT k.dedupe_key,
    json_patch('{}', json_object(
      'eventName', 'statsig::config_exposure',
      'user', json(u.user),
      'time', :time,
      'metadata', json_object(
        'config', :name,
        'ruleID', :rule_id,
        'reason', :reason,
        'time', coalesce(CAST(:received_at AS TEXT), 'null'),
        'lcut', coalesce(CAST(:lcut AS TEXT), 'null'),
        'bootstrapMetadata', json(s.bootstrap_metadata),
        'rulePassed', CASE :rule_passed WHEN 1 THEN 'true' WHEN 0 THEN 'false' END,
        'isManualExposure', CASE WHEN :manual THEN 'true' END),
      'secondaryExposures', json(coalesce(:secondary, '[]'))))
  FROM k, session AS s, logging_user AS u
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
SELECT count(*) >= 50 AS should_flush FROM event_queue;


-- name: log_layer_exposure
-- @defer
-- @coalesce
-- @quiet: 600000
-- @reads: values
-- @writes: events
-- A layer parameter was read. Explicit parameters (owned by the allocated experiment) expose the
-- full secondary exposures and the experiment; others only the undelegated exposures.
INSERT INTO event_queue (dedupe_key, event)
  WITH p AS (
  SELECT x.*, CASE WHEN x.is_explicit THEN coalesce(:allocated, '') ELSE '' END AS allocated_shown
  FROM (SELECT EXISTS (SELECT 1 FROM json_each(coalesce(:explicit, '[]'))
                       WHERE value = :parameter) AS is_explicit) AS x),
k AS (
  SELECT p.*,
    json_array('layer', :name, :rule_id, p.allocated_shown, :parameter, p.is_explicit, :reason)
      AS dedupe_key
  FROM p)
  SELECT k.dedupe_key,
    json_patch('{}', json_object(
      'eventName', 'statsig::layer_exposure',
      'user', json(u.user),
      'time', :time,
      'metadata', json_object(
        'config', :name,
        'ruleID', :rule_id,
        'allocatedExperiment', k.allocated_shown,
        'parameterName', :parameter,
        'isExplicitParameter', CASE WHEN k.is_explicit THEN 'true' ELSE 'false' END,
        'reason', :reason,
        'time', coalesce(CAST(:received_at AS TEXT), 'null'),
        'bootstrapMetadata', json(s.bootstrap_metadata),
        'isManualExposure', CASE WHEN :manual THEN 'true' END),
      'secondaryExposures', json(CASE WHEN k.is_explicit
        THEN coalesce(:secondary, '[]') ELSE coalesce(:undelegated, '[]') END)))
  FROM k, session AS s, logging_user AS u
  WHERE NOT EXISTS (SELECT 1 FROM exposure_seen AS e
                    WHERE e.dedupe_key = k.dedupe_key AND e.logged_at > :time - 600000);
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
INSERT INTO event_queue (dedupe_key, event)
  SELECT NULL, json_object(
    'eventName', 'statsig::non_exposed_checks',
    -- '' || : the original sends the counts as a JSON-encoded string
    'metadata', json_object('checks', '' || (SELECT json_group_object(name, n) FROM non_exposed)),
    'time', now_ms)
  FROM clock WHERE EXISTS (SELECT 1 FROM non_exposed);
DELETE FROM non_exposed;
DELETE FROM hash_memo WHERE rowid <= (SELECT max(rowid) FROM hash_memo) - 2048;
DELETE FROM batch;
INSERT INTO batch (max_id, n, body)
  SELECT max(id), count(*),
    json_object('events', json_group_array(json(event)), 'statsigMetadata', json(:metadata))
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
INSERT INTO event_queue (dedupe_key, event)
  SELECT NULL, json_object(
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

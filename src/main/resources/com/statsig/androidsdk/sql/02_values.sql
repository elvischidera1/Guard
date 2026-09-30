-- Sessions, cached values, overrides and evaluation lookups (the original Store).

-- name: schema

-- IDs compared by the bootstrap block.
CREATE TEMP TABLE IF NOT EXISTS bootstrap_user_ids (key TEXT, value TEXT);
CREATE TEMP TABLE IF NOT EXISTS bootstrap_evaluated_ids (key TEXT, value TEXT);

-- Scratch row through which every set of values (network, cache, bootstrap) is applied.
CREATE TEMP TABLE IF NOT EXISTS values_work (
  payload TEXT NOT NULL,             -- initialize response JSON (v1 or compact "init-v2")
  user_hash TEXT NOT NULL,           -- user the values were computed for ('' if unknown)
  source TEXT NOT NULL,              -- EvalSource to report from now on
  set_received INTEGER NOT NULL,     -- 1: replace session.received_at with received_at
  received_at INTEGER,
  bootstrap_metadata TEXT
);

-- Replaces the values in use: header fields on `session`, rows in `entity`.
CREATE TEMP TRIGGER IF NOT EXISTS values_apply AFTER INSERT ON values_work
BEGIN
  UPDATE session SET
    source = NEW.source,
    received_at = CASE WHEN NEW.set_received THEN NEW.received_at ELSE received_at END,
    values_user_hash = NEW.user_hash,
    lcut = coalesce(json_extract(NEW.payload, '$.time'), 0),
    has_updates = coalesce(json_extract(NEW.payload, '$.has_updates'), 0),
    hash_used = json_extract(NEW.payload, '$.hash_used'),
    derived_fields = json_extract(NEW.payload, '$.derived_fields'),
    full_checksum = json_extract(NEW.payload, '$.full_checksum'),
    param_stores = json_extract(NEW.payload, '$.param_stores'),
    sdk_flags = json_extract(NEW.payload, '$.sdk_flags'),
    sdk_configs = json_extract(NEW.payload, '$.sdk_configs'),
    bootstrap_metadata = NEW.bootstrap_metadata;

  DELETE FROM entity;

  -- v1: entities are stored as served.
  INSERT OR REPLACE INTO entity (kind, name, spec)
    SELECT 'gate', key, value FROM json_each(NEW.payload, '$.feature_gates')
      WHERE type = 'object' AND json_extract(NEW.payload, '$.response_format') IS NOT 'init-v2'
    UNION ALL
    SELECT 'config', key, value FROM json_each(NEW.payload, '$.dynamic_configs')
      WHERE type = 'object' AND json_extract(NEW.payload, '$.response_format') IS NOT 'init-v2'
    UNION ALL
    SELECT 'layer', key, value FROM json_each(NEW.payload, '$.layer_configs')
      WHERE type = 'object' AND json_extract(NEW.payload, '$.response_format') IS NOT 'init-v2'
    UNION ALL
    SELECT 'param_store', key, value FROM json_each(NEW.payload, '$.param_stores')
      WHERE type = 'object';

  -- init-v2: short keys, values and exposures shared through lookup tables. Expanded to v1.
  INSERT OR REPLACE INTO entity (kind, name, spec)
    SELECT 'gate', g.key, json_object(
        'name', g.key,
        'value', json(CASE WHEN json_extract(g.value, '$.v') THEN 'true' ELSE 'false' END),
        'rule_id', coalesce(json_extract(g.value, '$.r'), 'default'),
        'group_name', json_extract(g.value, '$.gn'),
        'secondary_exposures', json((
          SELECT json_group_array(json(x.value))
          FROM json_each(g.value, '$.s') AS i
            JOIN json_each(NEW.payload, '$.exposures') AS x ON x.key = i.value)),
        'id_type', json_extract(g.value, '$.i'))
    FROM json_each(NEW.payload, '$.feature_gates') AS g
    WHERE json_extract(NEW.payload, '$.response_format') = 'init-v2';

  INSERT OR REPLACE INTO entity (kind, name, spec)
    SELECT CASE c.path WHEN '$.dynamic_configs' THEN 'config' ELSE 'layer' END, c.key, json_object(
        'name', c.key,
        'value', json(coalesce(
          (SELECT v.value FROM json_each(NEW.payload, '$.values') AS v
           WHERE v.key = json_extract(c.value, '$.v')), '{}')),
        'rule_id', coalesce(json_extract(c.value, '$.r'), 'default'),
        'group_name', json_extract(c.value, '$.gn'),
        'secondary_exposures', json((
          SELECT json_group_array(json(x.value))
          FROM json_each(c.value, '$.s') AS i
            JOIN json_each(NEW.payload, '$.exposures') AS x ON x.key = i.value)),
        'undelegated_secondary_exposures', json((
          SELECT json_group_array(json(x.value))
          FROM json_each(c.value, '$.us') AS i
            JOIN json_each(NEW.payload, '$.exposures') AS x ON x.key = i.value)),
        'is_device_based', json(CASE WHEN json_extract(c.value, '$.d') THEN 'true' ELSE 'false' END),
        'is_user_in_experiment', json(CASE WHEN json_extract(c.value, '$.ue') THEN 'true' ELSE 'false' END),
        'is_experiment_active', json(CASE WHEN json_extract(c.value, '$.ea') THEN 'true' ELSE 'false' END),
        'allocated_experiment_name', json_extract(c.value, '$.ae'),
        'explicit_parameters', json(coalesce(json_extract(c.value, '$.ep'), '[]')),
        'passed', json(CASE json_type(c.value, '$.p') WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' END),
        'parameter_rule_ids', json(json_extract(c.value, '$.pr')))
    FROM (
      SELECT key, value, '$.dynamic_configs' AS path FROM json_each(NEW.payload, '$.dynamic_configs')
      UNION ALL
      SELECT key, value, '$.layer_configs' FROM json_each(NEW.payload, '$.layer_configs')
    ) AS c
    WHERE json_extract(NEW.payload, '$.response_format') = 'init-v2';

  DELETE FROM values_work;
END;

-- The entity being looked up by the current get_* block, and the view that turns it into the
-- columns every model object is built from.
CREATE TEMP TABLE IF NOT EXISTS lookup (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  name_hash TEXT,                    -- name as keyed by the server
  override TEXT,                     -- local override JSON, if any
  spec TEXT,                         -- entity JSON (served or sticky)
  reason TEXT NOT NULL,              -- EvalReason name
  latest TEXT,                       -- sticky bookkeeping (get_experiment / get_layer)
  sticky TEXT,
  latest_exp_active INTEGER
);

CREATE TEMP VIEW IF NOT EXISTS lookup_result AS
SELECT
  l.kind,
  l.name,
  l.override IS NOT NULL AS overridden,
  CASE
    WHEN l.override IS NOT NULL THEN l.override
    WHEN l.spec IS NULL THEN NULL
    WHEN l.kind = 'gate' THEN CASE WHEN json_extract(l.spec, '$.value') THEN 'true' ELSE 'false' END
    WHEN l.kind = 'param_store' THEN l.spec
    ELSE json_extract(l.spec, '$.value')
  END AS value,
  CASE WHEN l.override IS NOT NULL THEN 'override'
       WHEN l.spec IS NULL THEN ''
       ELSE json_extract(l.spec, '$.rule_id') END AS rule_id,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.group_name') END AS group_name,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.id_type') END AS id_type,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.secondary_exposures') END AS secondary_exposures,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.undelegated_secondary_exposures') END AS undelegated_secondary_exposures,
  CASE WHEN l.override IS NULL THEN coalesce(json_extract(l.spec, '$.is_user_in_experiment'), 0) ELSE 0 END AS is_user_in_experiment,
  CASE WHEN l.override IS NULL THEN coalesce(json_extract(l.spec, '$.is_experiment_active'), 0) ELSE 0 END AS is_experiment_active,
  CASE WHEN l.override IS NULL THEN coalesce(json_extract(l.spec, '$.is_device_based'), 0) ELSE 0 END AS is_device_based,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.allocated_experiment_name') END AS allocated_experiment_name,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.explicit_parameters') END AS explicit_parameters,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.passed') END AS rule_passed,
  CASE WHEN l.override IS NULL THEN json_extract(l.spec, '$.parameter_rule_ids') END AS parameter_rule_ids,
  s.source,
  l.reason,
  s.lcut,
  s.received_at
FROM lookup AS l, session AS s;

-- How entity names are hashed for the values in use: NULL means names are served as-is.
CREATE TEMP VIEW IF NOT EXISTS name_hash_algo AS
SELECT CASE hash_used WHEN 'djb2' THEN 'djb2' WHEN 'none' THEN NULL ELSE 'sha256' END AS algo
FROM session;


-- name: session_start
-- Starts a client session for :user (evaluation copy, JSON). Clears all per-client state.
DELETE FROM session;
DELETE FROM entity;
DELETE FROM memory_values;
DELETE FROM lookup;
DELETE FROM event_queue;
DELETE FROM exposure_seen;
DELETE FROM non_exposed;
DELETE FROM marker;
INSERT INTO hash_input (algo, input)
  SELECT 'djb2', :user
  WHERE NOT EXISTS (SELECT 1 FROM hash_memo WHERE algo = 'djb2' AND input = :user);
INSERT INTO session (id, sdk_key, user, first_user, user_hash, scoped_key, cache_key, source, options)
  SELECT 1, :sdk_key, :user, :user, output, :scoped_key, output || ':' || :sdk_key, 'Uninitialized', :options
  FROM hash_memo WHERE algo = 'djb2' AND input = :user;


-- name: stable_id
-- The device's stable ID, created (UUID v4) on first use.
INSERT OR IGNORE INTO setting (key, value)
  SELECT 'stable_id',
    lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4'
    || substr(lower(hex(randomblob(2))), 2) || '-'
    || substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-'
    || lower(hex(randomblob(6)));
SELECT value AS stable_id FROM setting WHERE key = 'stable_id';


-- name: set_user
-- updateUser: switch the session to another user. Values stay until load_cache replaces them.
INSERT INTO hash_input (algo, input)
  SELECT 'djb2', :user
  WHERE NOT EXISTS (SELECT 1 FROM hash_memo WHERE algo = 'djb2' AND input = :user);
UPDATE session SET
  user = :user,
  user_hash = (SELECT output FROM hash_memo WHERE algo = 'djb2' AND input = :user),
  scoped_key = :scoped_key,
  cache_key = (SELECT output FROM hash_memo WHERE algo = 'djb2' AND input = :user) || ':' || sdk_key,
  source = 'Uninitialized',
  received_at = NULL;
DELETE FROM exposure_seen;


-- name: load_cache
-- Makes the cached values for the session's user current (source Cache), or clears the values.
-- Lookup order: this client's bootstrap values, then disk by exact key, then disk through the
-- custom cache key mapping.
UPDATE session SET source = 'Loading';
INSERT INTO values_work (payload, user_hash, source, set_received, received_at, bootstrap_metadata)
  SELECT coalesce(found.payload, '{}'),
    coalesce(found.user_hash, ''),
    CASE WHEN found.payload IS NULL THEN 'Loading' ELSE 'Cache' END,
    found.payload IS NOT NULL,
    found.received_at,
    found.bootstrap_metadata
  FROM session AS s
    LEFT JOIN (
      SELECT 1 AS priority, m.payload, '' AS user_hash, c.now_ms AS received_at, m.bootstrap_metadata
      FROM memory_values AS m, session AS s, clock AS c WHERE m.cache_key = s.cache_key
      UNION ALL
      SELECT 2, m.payload, '', c.now_ms, m.bootstrap_metadata
      FROM memory_values AS m, session AS s, clock AS c WHERE m.scoped_key = s.scoped_key
      UNION ALL
      SELECT 3, v.payload, v.user_hash, v.received_at, NULL
      FROM cached_values AS v, session AS s WHERE v.cache_key = s.cache_key
      UNION ALL
      SELECT 4, v.payload, v.user_hash, v.received_at, NULL
      FROM cached_values AS v, cache_key_map AS k, session AS s
      WHERE k.scoped_key = s.scoped_key AND v.cache_key = k.cache_key
      ORDER BY priority LIMIT 1
    ) AS found;


-- name: save_values
-- A successful initialize response for :user / :scoped_key (the session's user when the request
-- was made). Ignored if the session has moved on to another user. Returns has_updates/applied.
INSERT INTO values_work (payload, user_hash, source, set_received, received_at, bootstrap_metadata)
  SELECT :payload, s.user_hash, 'Network', 1, c.now_ms, NULL
  FROM session AS s, clock AS c
  WHERE s.user = :user AND s.scoped_key = :scoped_key AND json_extract(:payload, '$.has_updates');
UPDATE session SET source = 'NetworkNotModified', received_at = (SELECT now_ms FROM clock)
  WHERE user = :user AND scoped_key = :scoped_key AND NOT coalesce(json_extract(:payload, '$.has_updates'), 0);
-- Persist, remember which user the custom cache key points to, and keep the 10 most recent.
INSERT OR REPLACE INTO cached_values (cache_key, user_hash, payload, received_at)
  SELECT s.cache_key, s.user_hash, :payload, s.received_at FROM session AS s
  WHERE s.user = :user AND s.scoped_key = :scoped_key AND json_extract(:payload, '$.has_updates');
DELETE FROM memory_values
  WHERE cache_key = (SELECT cache_key FROM session WHERE user = :user AND scoped_key = :scoped_key)
    AND json_extract(:payload, '$.has_updates');
INSERT OR REPLACE INTO cache_key_map (scoped_key, cache_key, last_used_at)
  SELECT s.scoped_key, s.cache_key, c.now_ms FROM session AS s, clock AS c
  WHERE s.user = :user AND s.scoped_key = :scoped_key AND json_extract(:payload, '$.has_updates');
DELETE FROM cache_key_map WHERE scoped_key IN (
  SELECT scoped_key FROM cache_key_map WHERE scoped_key <> :scoped_key
  ORDER BY last_used_at, scoped_key
  LIMIT max(0, (SELECT count(*) FROM cache_key_map) - 10));
DELETE FROM cached_values WHERE cache_key NOT IN (SELECT cache_key FROM cache_key_map);
DELETE FROM sticky_value
  WHERE owner <> '' AND owner <> (SELECT cache_key FROM session)
    AND owner NOT IN (SELECT cache_key FROM cache_key_map);
SELECT coalesce(json_extract(:payload, '$.has_updates'), 0) AS has_updates,
  EXISTS (SELECT 1 FROM session WHERE user = :user AND scoped_key = :scoped_key) AS applied;


-- name: network_failed
-- The initialize request failed: without cached values there is nothing to evaluate with.
UPDATE session SET source = 'NoValues' WHERE source <> 'Cache';


-- name: bootstrap
-- Values provided by the app (StatsigOptions.initializeValues / updateUser(values)). They are
-- used as-is for this client and never persisted. evaluated_keys, when present, must match the
-- user's IDs (userID + customIDs, stableID ignored) or the source is InvalidBootstrap.
INSERT INTO bootstrap_user_ids (key, value)
  SELECT key, value FROM json_each((SELECT user FROM session), '$.customIDs')
    WHERE key <> 'stableID' AND type IN ('text', 'null')
  UNION
  SELECT 'userID', json_extract(user, '$.userID') FROM session
    WHERE json_extract(user, '$.userID') IS NOT NULL;
-- evaluated_keys may nest maps (e.g. customIDs); their string entries count, arrays do not
INSERT INTO bootstrap_evaluated_ids (key, value)
  SELECT key, value FROM json_tree(:values, '$.evaluated_keys')
  WHERE type IN ('text', 'null') AND key <> 'stableID'
    AND fullkey NOT LIKE '%[%' AND fullkey NOT LIKE '%.stableID.%';
INSERT INTO values_work (payload, user_hash, source, set_received, received_at, bootstrap_metadata)
  SELECT :values, '',
    CASE WHEN json_type(:values, '$.evaluated_keys') IS NOT 'object' THEN 'Bootstrap'
      WHEN NOT EXISTS (SELECT * FROM bootstrap_user_ids EXCEPT SELECT * FROM bootstrap_evaluated_ids)
        AND NOT EXISTS (SELECT * FROM bootstrap_evaluated_ids EXCEPT SELECT * FROM bootstrap_user_ids)
      THEN 'Bootstrap'
      ELSE 'InvalidBootstrap' END,
    0, NULL,
    nullif(json_patch('{}', json_object(
      'generatorSDKInfo', CASE WHEN json_type(:values, '$.sdkInfo') = 'object' THEN json_patch('{}', json_object(
        'sdkType', json_extract(:values, '$.sdkInfo.sdkType'),
        'sdkVersion', json_extract(:values, '$.sdkInfo.sdkVersion'))) END,
      'lcut', CASE WHEN json_type(:values, '$.time') IN ('integer', 'real')
        THEN CAST(json_extract(:values, '$.time') AS INTEGER) END,
      'user', CASE WHEN json_type(:values, '$.user') = 'object' THEN json_patch('{}', json_object(
        'userID', json_extract(:values, '$.user.userID'),
        'email', json_extract(:values, '$.user.email'),
        'ip', json_extract(:values, '$.user.ip'),
        'userAgent', json_extract(:values, '$.user.userAgent'),
        'country', json_extract(:values, '$.user.country'),
        'locale', json_extract(:values, '$.user.locale'),
        'appVersion', json_extract(:values, '$.user.appVersion'),
        'custom', json(json_extract(:values, '$.user.custom')),
        'customIDs', json(json_extract(:values, '$.user.customIDs')),
        'statsigEnvironment', json(json_extract(:values, '$.user.statsigEnvironment')))) END)), '{}')
  FROM session AS s;
DELETE FROM bootstrap_user_ids;
DELETE FROM bootstrap_evaluated_ids;
INSERT OR REPLACE INTO memory_values (cache_key, scoped_key, payload, bootstrap_metadata)
  SELECT cache_key, scoped_key, :values, bootstrap_metadata FROM session;


-- name: session_state
-- Global evaluation details plus what the network layer needs.
SELECT source, lcut, received_at, sdk_key, user, scoped_key,
  json_type(sdk_flags, '$.enable_log_event_compression') = 'true' AS compress_custom_urls
FROM session;


-- name: values_json
-- The values in use, as a v1 initialize response (getInitializeResponseJson, debug view).
SELECT json_object(
    'feature_gates', json((SELECT json_group_object(name, json(spec)) FROM entity WHERE kind = 'gate')),
    'dynamic_configs', json((SELECT json_group_object(name, json(spec)) FROM entity WHERE kind = 'config')),
    'layer_configs', json((SELECT json_group_object(name, json(spec)) FROM entity WHERE kind = 'layer')),
    'has_updates', json(CASE WHEN has_updates THEN 'true' ELSE 'false' END),
    'hash_used', hash_used,
    'time', lcut,
    'derived_fields', json(derived_fields),
    'param_stores', json(param_stores),
    'full_checksum', full_checksum,
    'sdk_flags', json(sdk_flags),
    'sdk_configs', json(sdk_configs)) AS json,
  source, lcut, received_at
FROM session;


-- name: get_gate
-- checkGate: local override, else the served gate (by name, then by hashed name).
INSERT INTO hash_input (algo, input)
  SELECT algo, :name FROM name_hash_algo
  WHERE algo IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM entity WHERE kind = 'gate' AND name = :name)
    AND NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = name_hash_algo.algo AND m.input = :name);
INSERT OR REPLACE INTO lookup (id, kind, name, override, spec, reason)
  SELECT 1, 'gate', :name, o.value, e.spec,
    CASE WHEN o.value IS NOT NULL THEN 'LocalOverride' WHEN e.spec IS NOT NULL THEN 'Recognized' ELSE 'Unrecognized' END
  FROM name_hash_algo AS a
    LEFT JOIN local_override AS o ON o.kind = 'gate' AND o.name = :name
    LEFT JOIN entity AS e ON e.kind = 'gate' AND e.name = coalesce(
      (SELECT name FROM entity WHERE kind = 'gate' AND name = :name),
      (SELECT output FROM hash_memo AS m WHERE m.algo = a.algo AND m.input = :name));
SELECT * FROM lookup_result;


-- name: get_config
-- getConfig: local override, else the served config. Never sticky.
INSERT INTO hash_input (algo, input)
  SELECT algo, :name FROM name_hash_algo
  WHERE algo IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM entity WHERE kind = 'config' AND name = :name)
    AND NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = name_hash_algo.algo AND m.input = :name);
INSERT OR REPLACE INTO lookup (id, kind, name, override, spec, reason)
  SELECT 1, 'config', :name, o.value, e.spec,
    CASE WHEN o.value IS NOT NULL THEN 'LocalOverride' WHEN e.spec IS NOT NULL THEN 'Recognized' ELSE 'Unrecognized' END
  FROM name_hash_algo AS a
    LEFT JOIN local_override AS o ON o.kind = 'config' AND o.name = :name
    LEFT JOIN entity AS e ON e.kind = 'config' AND e.name = coalesce(
      (SELECT name FROM entity WHERE kind = 'config' AND name = :name),
      (SELECT output FROM hash_memo AS m WHERE m.algo = a.algo AND m.input = :name));
SELECT * FROM lookup_result;


-- name: get_param_store
INSERT INTO hash_input (algo, input)
  SELECT algo, :name FROM name_hash_algo
  WHERE algo IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM entity WHERE kind = 'param_store' AND name = :name)
    AND NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = name_hash_algo.algo AND m.input = :name);
INSERT OR REPLACE INTO lookup (id, kind, name, override, spec, reason)
  SELECT 1, 'param_store', :name, NULL, e.spec,
    CASE WHEN e.spec IS NOT NULL THEN 'Recognized' ELSE 'Unrecognized' END
  FROM name_hash_algo AS a
    LEFT JOIN entity AS e ON e.kind = 'param_store' AND e.name = coalesce(
      (SELECT name FROM entity WHERE kind = 'param_store' AND name = :name),
      (SELECT output FROM hash_memo AS m WHERE m.algo = a.algo AND m.input = :name));
SELECT * FROM lookup_result;


-- name: get_experiment
-- getExperiment (:kind = 'config') and getLayer (:kind = 'layer'), with sticky values.
-- With :keep = 1 the first value served while the user is in an active experiment is kept
-- ("sticky") for as long as that experiment stays active. With :keep = 0 any kept value is
-- dropped. Layers check the experiment the kept value was allocated to.
INSERT INTO hash_input (algo, input)
  SELECT algo, :name FROM name_hash_algo
  WHERE algo IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = name_hash_algo.algo AND m.input = :name);
INSERT OR REPLACE INTO lookup (id, kind, name, name_hash, override, latest, sticky, reason)
  SELECT 1, :kind, :name, h.name_hash,
    (SELECT value FROM local_override WHERE kind = :kind AND name = :name),
    (SELECT spec FROM entity WHERE kind = :kind AND name IN (:name, h.name_hash)
      ORDER BY name = :name DESC LIMIT 1),
    coalesce(
      (SELECT spec FROM sticky_value WHERE owner = s.cache_key AND name_hash = h.name_hash),
      (SELECT spec FROM sticky_value WHERE owner = '' AND name_hash = h.name_hash)),
    ''
  FROM session AS s,
    (SELECT coalesce((SELECT output FROM hash_memo AS m WHERE m.algo = a.algo AND m.input = :name), :name) AS name_hash
     FROM name_hash_algo AS a) AS h;
UPDATE lookup SET latest_exp_active = coalesce(
  CASE WHEN kind = 'layer'
    THEN (SELECT json_extract(spec, '$.is_experiment_active') FROM entity
          WHERE kind = 'config' AND name = json_extract(lookup.sticky, '$.allocated_experiment_name'))
    ELSE json_extract(latest, '$.is_experiment_active') END, 0);
-- drop the kept value: not wanted, or neither the kept nor the latest experiment is active
DELETE FROM sticky_value
  WHERE name_hash = (SELECT name_hash FROM lookup)
    AND owner IN ((SELECT cache_key FROM session), '')
    AND EXISTS (SELECT 1 FROM lookup WHERE override IS NULL AND (
      NOT :keep
      OR (sticky IS NOT NULL AND NOT latest_exp_active
          AND NOT coalesce(json_extract(latest, '$.is_experiment_active'), 0))));
-- keep the latest value: wanted, nothing kept (or the kept one is over), user in an active experiment
INSERT OR REPLACE INTO sticky_value (owner, name_hash, spec)
  SELECT CASE WHEN json_extract(l.latest, '$.is_device_based') THEN '' ELSE s.cache_key END,
    l.name_hash, l.latest
  FROM lookup AS l, session AS s
  WHERE l.override IS NULL AND :keep
    AND json_extract(l.latest, '$.is_experiment_active')
    AND json_extract(l.latest, '$.is_user_in_experiment')
    AND (l.sticky IS NULL OR NOT l.latest_exp_active);
UPDATE lookup SET
  spec = CASE WHEN override IS NULL AND :keep AND sticky IS NOT NULL AND latest_exp_active
    THEN sticky ELSE latest END,
  reason = CASE
    WHEN override IS NOT NULL THEN 'LocalOverride'
    WHEN :keep AND sticky IS NOT NULL AND latest_exp_active THEN 'Sticky'
    WHEN latest IS NOT NULL THEN 'Recognized'
    ELSE 'Unrecognized' END;
SELECT * FROM lookup_result;


-- name: override_set
INSERT OR REPLACE INTO local_override (kind, name, value) VALUES (:kind, :name, :value);

-- name: override_remove
DELETE FROM local_override WHERE name = :name;

-- name: override_clear
DELETE FROM local_override;

-- name: overrides
SELECT kind, name, value FROM local_override ORDER BY kind, name;

-- Sessions, cached values, overrides and evaluation lookups (the original Store).

-- name: schema

-- The shared "values" and "exposures" tables of an init-v2 payload, by id, while it is applied.
CREATE TEMP TABLE IF NOT EXISTS v2_value (id TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
CREATE TEMP TABLE IF NOT EXISTS v2_exposure (id TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;

-- IDs compared by the bootstrap block.
CREATE TEMP TABLE IF NOT EXISTS bootstrap_user_ids (key TEXT, value TEXT);
CREATE TEMP TABLE IF NOT EXISTS bootstrap_evaluated_ids (key TEXT, value TEXT);

-- Scratch row through which every set of values (network, cache, bootstrap) is applied.
CREATE TEMP TABLE IF NOT EXISTS values_work (
  payload TEXT,                      -- initialize response JSON (v1 or compact "init-v2"), or
  from_cache TEXT,                   -- the cached_values key of values stored as applied, with
  cached_header TEXT,                -- their header

  user_hash TEXT NOT NULL,           -- user the values were computed for ('' if unknown)
  source TEXT NOT NULL,              -- EvalSource to report from now on
  set_received INTEGER NOT NULL,     -- 1: replace session.received_at with received_at
  received_at INTEGER,
  bootstrap_metadata TEXT,
  only_if_updates INTEGER NOT NULL DEFAULT 0, -- 1: apply only if the payload says has_updates
  -- The top-level fields values_apply reads, in one pass over the payload (each pass over a
  -- large payload costs milliseconds): time, has_updates, hash_used, derived_fields,
  -- full_checksum, param_stores, sdk_flags, sdk_configs, response_format.
  header TEXT GENERATED ALWAYS AS (CASE WHEN from_cache IS NULL THEN json_extract(payload,
    '$.time', '$.has_updates', '$.hash_used', '$.derived_fields', '$.full_checksum',
    '$.param_stores', '$.sdk_flags', '$.sdk_configs', '$.response_format')
    ELSE cached_header END) STORED
);

-- Replaces the values in use: header fields on `session`, rows in `entity`. The values_work row
-- stays until the block that inserted it deletes it.
CREATE TEMP TRIGGER IF NOT EXISTS values_apply AFTER INSERT ON values_work
WHEN NOT NEW.only_if_updates OR coalesce(NEW.header ->> 1, 0)
BEGIN
  UPDATE session SET
    source = NEW.source,
    received_at = CASE WHEN NEW.set_received THEN NEW.received_at ELSE received_at END,
    values_user_hash = NEW.user_hash,
    lcut = coalesce(NEW.header ->> 0, 0),
    has_updates = coalesce(NEW.header ->> 1, 0),
    hash_used = NEW.header ->> 2,
    derived_fields = NEW.header ->> 3,
    full_checksum = NEW.header ->> 4,
    param_stores = NEW.header ->> 5,
    sdk_flags = NEW.header ->> 6,
    sdk_configs = NEW.header ->> 7,
    bootstrap_metadata = NEW.bootstrap_metadata;

  DELETE FROM entity_body;

  -- v1 (the usual response): each entity object is stored as is.
  -- (One pass over the payload: the sections are read from its top-level members.)
  INSERT OR REPLACE INTO entity_body (kind, name, body)
    SELECT CASE s.key WHEN 'feature_gates' THEN 'gate' WHEN 'dynamic_configs' THEN 'config'
        WHEN 'layer_configs' THEN 'layer' ELSE 'param_store' END,
      e.key, e.value
    FROM json_each(NEW.payload) AS s, json_each(s.value) AS e
    WHERE s.type = 'object' AND e.type = 'object'
      AND (s.key = 'param_stores' OR (s.key IN ('feature_gates', 'dynamic_configs', 'layer_configs')
        AND NEW.header ->> 8 IS NOT 'init-v2'));

  -- Values stored as applied.
  INSERT OR REPLACE INTO entity_body (kind, name, body)
    SELECT kind, name, body FROM cached_entity WHERE cache_key = NEW.from_cache;

  -- init-v2 (compact bootstrap format): short keys; values and exposures are shared through
  -- lookup tables referenced by id (read once into v2_value / v2_exposure). For other formats
  -- json_each gets NULL, so the payload is not read again.
  DELETE FROM v2_value;
  DELETE FROM v2_exposure;
  INSERT OR REPLACE INTO v2_value (id, value)
    SELECT key, value
    FROM json_each(CASE WHEN NEW.header ->> 8 = 'init-v2' THEN NEW.payload END, '$.values');
  INSERT OR REPLACE INTO v2_exposure (id, value)
    SELECT key, value
    FROM json_each(CASE WHEN NEW.header ->> 8 = 'init-v2' THEN NEW.payload END, '$.exposures');
  INSERT OR REPLACE INTO entity_body (kind, name, body)
    SELECT k.kind, e.key, json_object(
      'value', CASE WHEN k.kind = 'gate' THEN json(CASE WHEN json_extract(e.value, '$.v') THEN 'true' ELSE 'false' END)
        ELSE json(coalesce((SELECT value FROM v2_value WHERE id = CAST(json_extract(e.value, '$.v') AS TEXT)), '{}')) END,
      'rule_id', coalesce(json_extract(e.value, '$.r'), 'default'),
      'group_name', json_extract(e.value, '$.gn'),
      'id_type', json_extract(e.value, '$.i'),
      'secondary_exposures', (SELECT json_group_array(json(value)) FROM (
         SELECT x.value FROM json_each(e.value, '$.s') AS i JOIN v2_exposure AS x ON x.id = CAST(i.value AS TEXT)
         ORDER BY i.key)),
      'undelegated_secondary_exposures', (SELECT json_group_array(json(value)) FROM (
         SELECT x.value FROM json_each(e.value, '$.us') AS i JOIN v2_exposure AS x ON x.id = CAST(i.value AS TEXT)
         ORDER BY i.key)),
      'is_user_in_experiment', e.value -> '$.ue',
      'is_experiment_active', e.value -> '$.ea',
      'is_device_based', e.value -> '$.d',
      'allocated_experiment_name', json_extract(e.value, '$.ae'),
      'explicit_parameters', json(coalesce(e.value -> '$.ep', '[]')),
      'passed', e.value -> '$.p',
      'parameter_rule_ids', e.value -> '$.pr')
    FROM (SELECT 'gate' AS kind, '$.feature_gates' AS path
          UNION ALL SELECT 'config', '$.dynamic_configs'
          UNION ALL SELECT 'layer', '$.layer_configs') AS k,
      json_each(CASE WHEN NEW.header ->> 8 = 'init-v2' THEN NEW.payload END, k.path) AS e
    WHERE e.type = 'object';
  DELETE FROM v2_value;
  DELETE FROM v2_exposure;
END;

-- An entity as JSON (the v1 response shape), for sticky values and getInitializeResponseJson.
CREATE TEMP VIEW IF NOT EXISTS entity_json AS
SELECT kind, name,
  CASE WHEN kind = 'param_store' THEN value ELSE json_object(
    'name', name,
    'value', json(value),
    'rule_id', rule_id,
    'group_name', group_name,
    'id_type', id_type,
    'secondary_exposures', json(coalesce(secondary_exposures, '[]')),
    'undelegated_secondary_exposures', json(coalesce(undelegated_secondary_exposures, '[]')),
    'is_user_in_experiment', json(CASE WHEN is_user_in_experiment THEN 'true' ELSE 'false' END),
    'is_experiment_active', json(CASE WHEN is_experiment_active THEN 'true' ELSE 'false' END),
    'is_device_based', json(CASE WHEN is_device_based THEN 'true' ELSE 'false' END),
    'allocated_experiment_name', allocated_experiment_name,
    'explicit_parameters', json(explicit_parameters),
    'passed', json(CASE passed WHEN 1 THEN 'true' WHEN 0 THEN 'false' END),
    'parameter_rule_ids', json(parameter_rule_ids)) END AS json
FROM entity;

-- The entity looked up by the current get_* block, and the view every model object is built from.
CREATE TEMP TABLE IF NOT EXISTS lookup (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  kind TEXT NOT NULL,
  name TEXT NOT NULL,                -- as asked for
  entity_name TEXT,                  -- as served (NULL if not served)
  name_hash TEXT,                    -- sticky values are keyed by the hashed name
  override TEXT,                     -- local override JSON, if any
  reason TEXT NOT NULL,              -- EvalReason name
  value TEXT,
  rule_id TEXT,
  group_name TEXT,
  id_type TEXT,
  secondary_exposures TEXT,
  undelegated_secondary_exposures TEXT,
  is_user_in_experiment INTEGER,
  is_experiment_active INTEGER,
  is_device_based INTEGER,
  allocated_experiment_name TEXT,
  explicit_parameters TEXT,
  passed INTEGER,
  parameter_rule_ids TEXT,
  sticky TEXT,                       -- kept value (JSON), get_experiment only
  latest_exp_active INTEGER,
  writes INTEGER                     -- whether this get_experiment changes kept values
);

-- (The session-wide details, source/lcut/received_at, come from session_state.)
CREATE TEMP VIEW IF NOT EXISTS lookup_result AS
SELECT
  l.override IS NOT NULL AS overridden,
  l.override IS NULL AND l.entity_name IS NOT NULL AS found,
  coalesce(l.override, l.value) AS value,
  CASE WHEN l.override IS NOT NULL THEN 'override' WHEN l.entity_name IS NULL THEN '' ELSE l.rule_id END AS rule_id,
  CASE WHEN l.override IS NULL THEN l.group_name END AS group_name,
  CASE WHEN l.override IS NULL THEN l.id_type END AS id_type,
  CASE WHEN l.override IS NULL THEN l.secondary_exposures END AS secondary_exposures,
  CASE WHEN l.override IS NULL THEN l.undelegated_secondary_exposures END AS undelegated_secondary_exposures,
  CASE WHEN l.override IS NULL THEN coalesce(l.is_user_in_experiment, 0) ELSE 0 END AS is_user_in_experiment,
  CASE WHEN l.override IS NULL THEN coalesce(l.is_experiment_active, 0) ELSE 0 END AS is_experiment_active,
  CASE WHEN l.override IS NULL THEN coalesce(l.is_device_based, 0) ELSE 0 END AS is_device_based,
  CASE WHEN l.override IS NULL THEN l.allocated_experiment_name END AS allocated_experiment_name,
  CASE WHEN l.override IS NULL THEN l.explicit_parameters END AS explicit_parameters,
  CASE WHEN l.override IS NULL THEN l.passed END AS rule_passed,
  CASE WHEN l.override IS NULL THEN l.parameter_rule_ids END AS parameter_rule_ids,
  l.reason
FROM lookup AS l;

-- How entity names are hashed for the values in use: NULL means names are served as-is.
CREATE TEMP VIEW IF NOT EXISTS name_hash_algo AS
SELECT CASE hash_used WHEN 'djb2' THEN 'djb2' WHEN 'none' THEN NULL ELSE 'sha256' END AS algo
FROM session;


-- name: session_start
-- @writes: values, events
-- Starts a client session for :user (evaluation copy, JSON). Clears all per-client state.
DELETE FROM session;
DELETE FROM entity_body;
DELETE FROM memory_values;
DELETE FROM lookup;
DELETE FROM event_queue;
DELETE FROM exposure_seen;
DELETE FROM non_exposed;
DELETE FROM marker;
DELETE FROM override;
INSERT INTO override (kind, name, value) SELECT kind, name, value FROM local_override;
INSERT INTO djb2_input (input)
  SELECT :user
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
-- @writes: values, events
-- updateUser: switch the session to another user. Values stay until load_cache replaces them.
DELETE FROM hash_memo WHERE rowid <= (SELECT max(rowid) FROM hash_memo) - 2048;
INSERT INTO djb2_input (input)
  SELECT :user
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
-- @writes: values
-- Makes the cached values for the session's user current (source Cache), or clears the values.
-- Lookup order: this client's bootstrap values, then disk by exact key, then disk through the
-- custom cache key mapping.
UPDATE session SET source = 'Loading';
INSERT INTO values_work
    (payload, from_cache, cached_header, user_hash, source, set_received, received_at,
     bootstrap_metadata)
  SELECT CASE WHEN found.priority IS NULL THEN '{}' ELSE found.payload END,
    found.from_cache,
    found.header,
    coalesce(found.user_hash, ''),
    CASE WHEN found.priority IS NULL THEN 'Loading' ELSE 'Cache' END,
    found.priority IS NOT NULL,
    found.received_at,
    found.bootstrap_metadata
  FROM session AS s
    LEFT JOIN (
      SELECT 1 AS priority, m.payload, NULL AS from_cache, NULL AS header, '' AS user_hash,
        c.now_ms AS received_at, m.bootstrap_metadata
      FROM memory_values AS m, session AS s, clock AS c WHERE m.cache_key = s.cache_key
      UNION ALL
      SELECT 2, m.payload, NULL, NULL, '', c.now_ms, m.bootstrap_metadata
      FROM memory_values AS m, session AS s, clock AS c WHERE m.scoped_key = s.scoped_key
      UNION ALL
      SELECT 3, NULL, v.cache_key, v.header, v.user_hash, v.received_at, NULL
      FROM cached_values AS v, session AS s WHERE v.cache_key = s.cache_key
      UNION ALL
      SELECT 4, NULL, v.cache_key, v.header, v.user_hash, v.received_at, NULL
      FROM cached_values AS v, cache_key_map AS k, session AS s
      WHERE k.scoped_key = s.scoped_key AND v.cache_key = k.cache_key
      ORDER BY priority LIMIT 1
    ) AS found;
DELETE FROM values_work;


-- name: save_values
-- @writes: values
-- A successful initialize response for :user / :scoped_key (the session's user when the request
-- was made). Ignored if the session has moved on to another user. Returns has_updates/applied.
-- The response is parsed once: values_work (present only if the session is still that user's)
-- carries has_updates for the later statements.
INSERT INTO values_work
    (payload, user_hash, source, set_received, received_at, bootstrap_metadata, only_if_updates)
  SELECT :payload, s.user_hash, 'Network', 1, c.now_ms, NULL, 1
  FROM session AS s, clock AS c
  WHERE s.user = :user AND s.scoped_key = :scoped_key;
UPDATE session SET source = 'NetworkNotModified', received_at = (SELECT now_ms FROM clock)
  WHERE EXISTS (SELECT 1 FROM values_work WHERE NOT coalesce(header ->> 1, 0));
-- Persist (as applied), remember which user the custom cache key points to, and keep the 10 most
-- recent.
INSERT OR REPLACE INTO cached_values (cache_key, user_hash, header, received_at)
  SELECT s.cache_key, s.user_hash, w.header, s.received_at FROM session AS s, values_work AS w
  WHERE w.header ->> 1;
DELETE FROM cached_entity
  WHERE cache_key = (SELECT cache_key FROM session)
    AND EXISTS (SELECT 1 FROM values_work WHERE header ->> 1);
INSERT INTO cached_entity (cache_key, kind, name, body)
  SELECT s.cache_key, e.kind, e.name, e.body FROM session AS s, entity_body AS e
  WHERE EXISTS (SELECT 1 FROM values_work WHERE header ->> 1);
DELETE FROM memory_values
  WHERE cache_key = (SELECT cache_key FROM session)
    AND EXISTS (SELECT 1 FROM values_work WHERE header ->> 1);
INSERT OR REPLACE INTO cache_key_map (scoped_key, cache_key, last_used_at)
  SELECT s.scoped_key, s.cache_key, c.now_ms FROM session AS s, clock AS c, values_work AS w
  WHERE w.header ->> 1;
DELETE FROM cache_key_map WHERE scoped_key IN (
  SELECT scoped_key FROM cache_key_map WHERE scoped_key <> :scoped_key
  ORDER BY last_used_at, scoped_key
  LIMIT max(0, (SELECT count(*) FROM cache_key_map) - 10));
DELETE FROM cached_entity WHERE cache_key IN (
  SELECT cache_key FROM cached_values WHERE cache_key NOT IN (SELECT cache_key FROM cache_key_map));
DELETE FROM cached_values WHERE cache_key NOT IN (SELECT cache_key FROM cache_key_map);
DELETE FROM sticky_value
  WHERE owner <> '' AND owner <> (SELECT cache_key FROM session)
    AND owner NOT IN (SELECT cache_key FROM cache_key_map);
SELECT coalesce((SELECT header ->> 1 FROM values_work), json_extract(:payload, '$.has_updates'), 0)
    AS has_updates,
  EXISTS (SELECT 1 FROM values_work) AS applied;
DELETE FROM values_work;


-- name: network_failed
-- @writes: values
-- The initialize request failed: without cached values there is nothing to evaluate with.
UPDATE session SET source = 'NoValues' WHERE source <> 'Cache';


-- name: bootstrap
-- @writes: values
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
DELETE FROM values_work;
DELETE FROM bootstrap_user_ids;
DELETE FROM bootstrap_evaluated_ids;
INSERT OR REPLACE INTO memory_values (cache_key, scoped_key, payload, bootstrap_metadata)
  SELECT cache_key, scoped_key, :values, bootstrap_metadata FROM session;


-- name: session_state
-- @cache
-- @reads: values
-- Global evaluation details plus what the network layer needs.
SELECT source, lcut, received_at, sdk_key, user, scoped_key,
  json_type(sdk_flags, '$.enable_log_event_compression') = 'true' AS compress_custom_urls
FROM session;


-- name: values_json
-- @cache
-- @reads: values
-- The values in use, as a v1 initialize response (getInitializeResponseJson, debug view).
SELECT json_object(
    'feature_gates', json((SELECT json_group_object(name, json(json)) FROM entity_json WHERE kind = 'gate')),
    'dynamic_configs', json((SELECT json_group_object(name, json(json)) FROM entity_json WHERE kind = 'config')),
    'layer_configs', json((SELECT json_group_object(name, json(json)) FROM entity_json WHERE kind = 'layer')),
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


-- name: get_value
-- @cache
-- @reads: values
-- checkGate / getConfig / getParameterStore (:kind 'gate' | 'config' | 'param_store'): the local
-- override, else the served entity (by name, then by hashed name). The columns of lookup_result
-- that gates, configs and parameter stores use (every returned column costs the host a read).
-- Like lookup_result, it leaves the session-wide details (source, lcut, received_at) to
-- session_state.
-- :name's djb2 is computed inline (the sum form of djb2_output) when the name allows it (up to
-- 512 characters, none outside the BMP); other hashes come from hash_memo. When the one needed is
-- not there yet, the row asks the host (_then) to run hash_name first and read again.
SELECT
  o.value IS NOT NULL AS overridden,
  o.value IS NULL AND e.name IS NOT NULL AS found,
  coalesce(o.value, e.value) AS value,
  CASE WHEN o.value IS NOT NULL THEN 'override' WHEN e.name IS NULL THEN '' ELSE e.rule_id END AS rule_id,
  CASE WHEN o.value IS NULL THEN e.group_name END AS group_name,
  CASE WHEN o.value IS NULL THEN e.id_type END AS id_type,
  CASE WHEN o.value IS NULL THEN e.secondary_exposures END AS secondary_exposures,
  CASE WHEN o.value IS NULL THEN coalesce(e.is_user_in_experiment, 0) ELSE 0 END AS is_user_in_experiment,
  CASE WHEN o.value IS NULL THEN coalesce(e.is_experiment_active, 0) ELSE 0 END AS is_experiment_active,
  CASE WHEN o.value IS NULL THEN coalesce(e.is_device_based, 0) ELSE 0 END AS is_device_based,
  CASE WHEN o.value IS NULL THEN e.allocated_experiment_name END AS allocated_experiment_name,
  CASE WHEN o.value IS NULL THEN e.passed END AS rule_passed,
  CASE WHEN o.value IS NOT NULL THEN 'LocalOverride' WHEN e.name IS NOT NULL THEN 'Recognized' ELSE 'Unrecognized' END AS reason,
  CASE WHEN e.name IS NULL AND s.hash_used IS NOT 'none' AND NOT (s.hash_used IS 'djb2' AND n.inline)
      AND NOT EXISTS (SELECT 1 FROM hash_memo WHERE algo = n.algo AND input = :name)
    THEN 'hash_name' END AS _then
FROM session AS s
  JOIN (SELECT CASE hash_used WHEN 'djb2' THEN 'djb2' ELSE 'sha256' END AS algo,
          length(:name) <= 512
            AND NOT :name GLOB '*[' || char(65536) || '-' || char(1114111) || ']*' AS inline
        FROM session) AS n
  LEFT JOIN override AS o ON o.kind = :kind AND o.name = :name
  LEFT JOIN entity AS e ON e.kind = :kind AND e.name = coalesce(
    (SELECT name FROM entity_body WHERE kind = :kind AND name = :name),
    CASE WHEN s.hash_used = 'djb2' AND n.inline
      THEN (SELECT CAST(sum(unicode(substr(:name, length(:name) - k, 1)) * p) & 4294967295 AS TEXT)
            FROM djb2_pow WHERE k < length(:name)) END,
    CASE WHEN s.hash_used IS NOT 'none'
      THEN (SELECT output FROM hash_memo WHERE algo = n.algo AND input = :name) END);


-- name: hash_name
-- @needs: sha256_schema
-- Memoizes the hash get_value needs for :name (sha256, or djb2 of names its inline form cannot
-- take).
INSERT INTO hash_input (algo, input)
  SELECT CASE s.hash_used WHEN 'djb2' THEN 'djb2' ELSE 'sha256' END, :name
  FROM session AS s
  WHERE s.hash_used IS NOT 'none'
    AND NOT EXISTS (SELECT 1 FROM hash_memo
      WHERE algo = CASE s.hash_used WHEN 'djb2' THEN 'djb2' ELSE 'sha256' END AND input = :name);


-- name: get_experiment
-- @needs: sha256_schema
-- @cache
-- @reads: values
-- @writes: values
-- getExperiment (:kind = 'config') and getLayer (:kind = 'layer'), with sticky values.
-- With :keep = 1 the first value served while the user is in an active experiment is kept
-- ("sticky") for as long as that experiment stays active. With :keep = 0 any kept value is
-- dropped. Layers check the experiment the kept value was allocated to.
INSERT INTO hash_input (algo, input)
  SELECT algo, :name FROM name_hash_algo
  WHERE algo IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = name_hash_algo.algo AND m.input = :name);
INSERT OR REPLACE INTO lookup (id, kind, name, entity_name, name_hash, override, reason, value,
    rule_id, group_name, id_type, secondary_exposures, undelegated_secondary_exposures,
    is_user_in_experiment, is_experiment_active, is_device_based, allocated_experiment_name,
    explicit_parameters, passed, parameter_rule_ids, sticky)
  SELECT 1, :kind, :name, e.name, h.name_hash,
    (SELECT value FROM override WHERE kind = :kind AND name = :name),
    '',
    e.value, e.rule_id, e.group_name, e.id_type, e.secondary_exposures, e.undelegated_secondary_exposures,
    e.is_user_in_experiment, e.is_experiment_active, e.is_device_based, e.allocated_experiment_name,
    e.explicit_parameters, e.passed, e.parameter_rule_ids,
    coalesce(
      (SELECT spec FROM sticky_value WHERE owner = s.cache_key AND name_hash = h.name_hash),
      (SELECT spec FROM sticky_value WHERE owner = '' AND name_hash = h.name_hash))
  FROM session AS s,
    (SELECT coalesce((SELECT output FROM hash_memo AS m WHERE m.algo = a.algo AND m.input = :name), :name) AS name_hash
     FROM name_hash_algo AS a) AS h
    LEFT JOIN entity AS e ON e.kind = :kind AND e.name = coalesce(
      (SELECT name FROM entity WHERE kind = :kind AND name = :name), h.name_hash);
UPDATE lookup SET latest_exp_active = coalesce(
  CASE WHEN kind = 'layer'
    THEN (SELECT is_experiment_active FROM entity
          WHERE kind = 'config' AND name = json_extract(lookup.sticky, '$.allocated_experiment_name'))
    ELSE is_experiment_active END, 0);
-- The same conditions as the DELETE / INSERT below, recorded so the result can say whether it
-- is repeatable (@cache): a call that changes kept values must not be answered from cache.
UPDATE lookup SET writes = override IS NULL AND (
  (sticky IS NOT NULL AND (NOT :keep OR (NOT latest_exp_active AND NOT coalesce(is_experiment_active, 0))))
  OR (:keep AND is_experiment_active AND is_user_in_experiment AND entity_name IS NOT NULL
      AND (sticky IS NULL OR NOT latest_exp_active)));
-- drop the kept value: not wanted, or neither the kept nor the latest experiment is active
DELETE FROM sticky_value
  WHERE name_hash = (SELECT name_hash FROM lookup)
    AND owner IN ((SELECT cache_key FROM session), '')
    AND EXISTS (SELECT 1 FROM lookup WHERE override IS NULL AND (
      NOT :keep OR (sticky IS NOT NULL AND NOT latest_exp_active AND NOT coalesce(is_experiment_active, 0))));
-- keep the latest value: wanted, nothing kept (or the kept experiment ended), user in an active experiment
INSERT OR REPLACE INTO sticky_value (owner, name_hash, spec)
  SELECT CASE WHEN l.is_device_based THEN '' ELSE s.cache_key END, l.name_hash, j.json
  FROM lookup AS l, session AS s JOIN entity_json AS j ON j.kind = l.kind AND j.name = l.entity_name
  WHERE l.override IS NULL AND :keep AND l.is_experiment_active AND l.is_user_in_experiment
    AND (l.sticky IS NULL OR NOT l.latest_exp_active);
-- serve the kept value while its experiment is active
UPDATE lookup SET
  entity_name = coalesce(entity_name, name_hash),
  value = sticky -> '$.value',
  rule_id = json_extract(sticky, '$.rule_id'),
  group_name = json_extract(sticky, '$.group_name'),
  id_type = json_extract(sticky, '$.id_type'),
  secondary_exposures = sticky -> '$.secondary_exposures',
  undelegated_secondary_exposures = sticky -> '$.undelegated_secondary_exposures',
  is_user_in_experiment = coalesce(json_extract(sticky, '$.is_user_in_experiment'), 0),
  is_experiment_active = coalesce(json_extract(sticky, '$.is_experiment_active'), 0),
  is_device_based = coalesce(json_extract(sticky, '$.is_device_based'), 0),
  allocated_experiment_name = json_extract(sticky, '$.allocated_experiment_name'),
  explicit_parameters = sticky -> '$.explicit_parameters',
  passed = json_extract(sticky, '$.passed'),
  parameter_rule_ids = sticky -> '$.parameter_rule_ids',
  reason = 'Sticky'
  WHERE override IS NULL AND :keep AND sticky IS NOT NULL AND latest_exp_active;
UPDATE lookup SET reason = CASE
    WHEN override IS NOT NULL THEN 'LocalOverride'
    WHEN entity_name IS NOT NULL THEN 'Recognized'
    ELSE 'Unrecognized' END
  WHERE reason = '';
SELECT r.*, NOT l.writes AS _cacheable FROM lookup_result AS r, lookup AS l;


-- name: override_set
-- @writes: values
-- Overrides apply to this client right away and are persisted for the next start.
INSERT OR REPLACE INTO override (kind, name, value) VALUES (:kind, :name, :value);
INSERT OR REPLACE INTO local_override (kind, name, value) VALUES (:kind, :name, :value);

-- name: override_remove
-- @writes: values
DELETE FROM override WHERE name = :name;
DELETE FROM local_override WHERE name = :name;

-- name: override_clear
-- @writes: values
DELETE FROM override;
DELETE FROM local_override;

-- name: overrides
-- @cache
-- @reads: values
SELECT kind, name, value FROM override ORDER BY kind, name;


-- name: legacy_import_pending
-- Whether data of the SharedPreferences-based SDK versions still has to be imported (once).
SELECT NOT EXISTS (SELECT 1 FROM setting WHERE key = 'legacy_imported') AS pending;

-- name: legacy_import
-- @writes: values
-- One-time import from the SharedPreferences-based SDK: the stable ID (so upgraded devices keep
-- their identity), local overrides ({"gates": .., "configs": .., "layers": ..}) and device-based
-- sticky experiments ({name hash: experiment}). Cached values are simply fetched again.
INSERT OR IGNORE INTO setting (key, value) SELECT 'stable_id', :stable_id WHERE :stable_id IS NOT NULL;
INSERT OR IGNORE INTO local_override (kind, name, value)
  SELECT k.kind, o.key, CASE WHEN k.kind = 'gate' THEN CASE WHEN o.value THEN 'true' ELSE 'false' END ELSE o.value END
  FROM (SELECT 'gate' AS kind, '$.gates' AS path UNION ALL SELECT 'config', '$.configs'
        UNION ALL SELECT 'layer', '$.layers') AS k,
    json_each(CASE WHEN json_valid(:overrides) THEN :overrides ELSE '{}' END, k.path) AS o;
INSERT OR IGNORE INTO sticky_value (owner, name_hash, spec)
  SELECT '', key, value FROM json_each(CASE WHEN json_valid(:device_sticky) THEN :device_sticky ELSE '{}' END)
  WHERE type = 'object';
INSERT OR REPLACE INTO override (kind, name, value) SELECT kind, name, value FROM local_override;
INSERT OR REPLACE INTO setting (key, value) VALUES ('legacy_imported', '1');

-- name: reset
DELETE FROM v2_value;
DELETE FROM v2_exposure;
DELETE FROM bootstrap_user_ids;
DELETE FROM bootstrap_evaluated_ids;
DELETE FROM values_work;
DELETE FROM lookup;

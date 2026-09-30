-- On-device evaluation of config specs (OnDeviceEvalAdapter): the rule/condition engine of the
-- original evaluator package.
--
-- Evaluating one gate/config/layer for a user runs:
--   eval_begin     works out every spec the answer depends on (nested gate conditions, config
--                  delegates), computes the hashes the rules need and lists regex matches the
--                  host must perform (str_matches),
--   <host>         fills regex_request.result (1 match, 0 no match, -1 invalid pattern),
--   eval_step      evaluates every spec whose dependencies are done; the host repeats it until
--                  it reports no progress (one round per level of gate nesting),
--   eval_result    returns the answer.

-- name: dcs_load
-- OnDeviceEvalAdapter.setData: replaces the specs (a payload that is not JSON is ignored).
-- The evaluator's tables are created by the first load, so that clients without on-device
-- evaluation never pay for them.

CREATE TEMP TABLE IF NOT EXISTS dcs (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  time INTEGER NOT NULL,
  received_at INTEGER NOT NULL
);

CREATE TEMP TABLE IF NOT EXISTS dcs_spec (
  kind TEXT NOT NULL,                -- 'gate' | 'config' | 'layer'
  name TEXT NOT NULL,
  spec TEXT NOT NULL,
  PRIMARY KEY (kind, name)
);

CREATE TEMP TABLE IF NOT EXISTS dcs_rule (
  kind TEXT NOT NULL,
  spec_name TEXT NOT NULL,
  idx INTEGER NOT NULL,
  rule TEXT NOT NULL,
  PRIMARY KEY (kind, spec_name, idx)
);

CREATE TEMP TABLE IF NOT EXISTS dcs_condition (
  kind TEXT NOT NULL,
  spec_name TEXT NOT NULL,
  rule_idx INTEGER NOT NULL,
  idx INTEGER NOT NULL,
  type TEXT,                         -- lower case
  operator TEXT,
  field TEXT,
  target TEXT,                       -- targetValue as JSON
  id_type TEXT,
  salt TEXT,                         -- additionalValues.salt as a string ('null' if absent)
  ref_gate TEXT,                     -- pass_gate / fail_gate target
  PRIMARY KEY (kind, spec_name, rule_idx, idx)
);

-- spec -> specs it may need: gates of gate conditions, configs of delegating rules
CREATE TEMP TABLE IF NOT EXISTS dcs_edge (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  dep_kind TEXT NOT NULL,
  dep_name TEXT NOT NULL,
  PRIMARY KEY (kind, name, dep_kind, dep_name)
);

CREATE TEMP TABLE IF NOT EXISTS dcs_param_store (
  name TEXT PRIMARY KEY,
  parameters TEXT NOT NULL
);

-- Evaluation workspace.
CREATE TEMP TABLE IF NOT EXISTS eval_user (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  user TEXT NOT NULL,
  now_ms INTEGER NOT NULL
);

CREATE TEMP TABLE IF NOT EXISTS eval_needed (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  PRIMARY KEY (kind, name)
);

CREATE TEMP TABLE IF NOT EXISTS eval_cond (
  kind TEXT NOT NULL,
  spec_name TEXT NOT NULL,
  rule_idx INTEGER NOT NULL,
  idx INTEGER NOT NULL,
  pass INTEGER NOT NULL,
  unsupported INTEGER NOT NULL,
  exposures TEXT NOT NULL,           -- JSON array
  PRIMARY KEY (kind, spec_name, rule_idx, idx)
);

CREATE TEMP TABLE IF NOT EXISTS eval_result (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  bool INTEGER NOT NULL,
  value TEXT,                        -- JSON
  rule_id TEXT,
  group_name TEXT,
  secondary TEXT NOT NULL,           -- JSON array
  undelegated TEXT NOT NULL,         -- JSON array
  is_experiment_group INTEGER NOT NULL,
  is_active INTEGER NOT NULL,
  config_delegate TEXT,
  explicit_parameters TEXT,          -- JSON array or NULL
  unsupported INTEGER NOT NULL,
  unrecognized INTEGER NOT NULL,
  PRIMARY KEY (kind, name)
);

-- What an evaluation needs per rule and per condition, worked out once by eval_begin.
CREATE TEMP TABLE IF NOT EXISTS eval_rule_ctx (
  kind TEXT NOT NULL,
  spec_name TEXT NOT NULL,
  idx INTEGER NOT NULL,
  bucket_input TEXT NOT NULL,        -- '<spec salt>.<rule salt>.<unit id>' (pass percentage)
  PRIMARY KEY (kind, spec_name, idx)
);

CREATE TEMP TABLE IF NOT EXISTS eval_cond_ctx (
  kind TEXT NOT NULL,
  spec_name TEXT NOT NULL,
  rule_idx INTEGER NOT NULL,
  idx INTEGER NOT NULL,
  type TEXT,
  operator TEXT,
  field TEXT,
  target TEXT,
  ref_gate TEXT,
  path TEXT,                         -- JSON path of the user attribute (user_field & co.)
  unit_id TEXT,
  bucket_input TEXT,                 -- '<salt>.<unit id>' (user_bucket)
  PRIMARY KEY (kind, spec_name, rule_idx, idx)
);

-- str_matches work for the host (and a memo of its answers).
CREATE TEMP TABLE IF NOT EXISTS regex_request (
  pattern TEXT NOT NULL,
  value TEXT NOT NULL,
  result INTEGER,                    -- 1 match, 0 no match, -1 invalid pattern, NULL pending
  PRIMARY KEY (pattern, value)
);

-- The needed conditions with what eval_begin stores for them. A user attribute is read like the
-- original: the well-known fields first, then custom, then privateAttributes (each by exact name,
-- then lower-cased), skipping empty strings. `path` is a JSON path into the user, NULL for null.
-- The unit ID is userID, or the customIDs entry for the condition's idType.
CREATE TEMP VIEW IF NOT EXISTS eval_cond_field AS
SELECT c.*,
  CASE WHEN lower(c.id_type) <> 'userid' AND c.id_type <> ''
      THEN coalesce(json_extract(u.user, '$.customIDs."' || c.id_type || '"'),
                    json_extract(u.user, '$.customIDs."' || lower(c.id_type) || '"'))
      ELSE json_extract(u.user, '$.userID') END AS unit_id,
  CASE WHEN c.type = 'user_bucket' THEN c.salt || '.' || coalesce(CASE WHEN lower(c.id_type) <> 'userid' AND c.id_type <> ''
        THEN coalesce(json_extract(u.user, '$.customIDs."' || c.id_type || '"'),
                      json_extract(u.user, '$.customIDs."' || lower(c.id_type) || '"'))
        ELSE json_extract(u.user, '$.userID') END, '') END AS bucket_input,
  CASE WHEN p2 IS NOT NULL AND coalesce(json_type(u.user, p2), 'null') <> 'null'
         AND NOT (json_type(u.user, p2) = 'text' AND json_extract(u.user, p2) = '') THEN p2
       WHEN json_type(u.user, '$.privateAttributes') = 'object' THEN
         CASE WHEN coalesce(json_type(u.user, '$.privateAttributes."' || c.field || '"'), 'null') <> 'null'
                THEN '$.privateAttributes."' || c.field || '"'
              WHEN coalesce(json_type(u.user, '$.privateAttributes."' || lower(c.field) || '"'), 'null') <> 'null'
                THEN '$.privateAttributes."' || lower(c.field) || '"' END
       ELSE p2 END AS path
FROM (
  SELECT c.*,
    CASE WHEN p1 IS NOT NULL AND NOT (json_type(u.user, p1) = 'text' AND json_extract(u.user, p1) = '') THEN p1
         WHEN json_type(u.user, '$.custom') = 'object' THEN
           CASE WHEN coalesce(json_type(u.user, '$.custom."' || c.field || '"'), 'null') <> 'null'
                  THEN '$.custom."' || c.field || '"'
                WHEN coalesce(json_type(u.user, '$.custom."' || lower(c.field) || '"'), 'null') <> 'null'
                  THEN '$.custom."' || lower(c.field) || '"' END
         ELSE p1 END AS p2
  FROM (
    SELECT c.*,
      CASE WHEN coalesce(json_type(u.user, top), 'null') <> 'null' THEN top END AS p1
    FROM (
      SELECT c.*,
        CASE lower(c.field)
          WHEN 'userid' THEN '$.userID' WHEN 'user_id' THEN '$.userID'
          WHEN 'email' THEN '$.email'
          WHEN 'ip' THEN '$.ip' WHEN 'ipaddress' THEN '$.ip' WHEN 'ip_address' THEN '$.ip'
          WHEN 'useragent' THEN '$.userAgent' WHEN 'user_agent' THEN '$.userAgent'
          WHEN 'country' THEN '$.country'
          WHEN 'locale' THEN '$.locale'
          WHEN 'appversion' THEN '$.appVersion' WHEN 'app_version' THEN '$.appVersion'
        END AS top
      FROM dcs_condition AS c JOIN eval_needed AS n ON n.kind = c.kind AND n.name = c.spec_name
    ) AS c, eval_user AS u
  ) AS c, eval_user AS u
) AS c, eval_user AS u;

-- Each value condition's left-hand side, as JSON (`v`), plus its string/number/epoch readings,
-- and the same readings of the target.
CREATE TEMP VIEW IF NOT EXISTS eval_cond_value AS
SELECT x.*,
  -- versions as JSON arrays of their dot-separated parts; a suffix after the first '-' is ignored
  CASE WHEN json_valid('["' || replace(CASE WHEN instr(x.vstr, '-') > 1 THEN substr(x.vstr, 1, instr(x.vstr, '-') - 1) ELSE x.vstr END, '.', '","') || '"]')
    THEN '["' || replace(CASE WHEN instr(x.vstr, '-') > 1 THEN substr(x.vstr, 1, instr(x.vstr, '-') - 1) ELSE x.vstr END, '.', '","') || '"]' END AS vversion,
  CASE WHEN json_valid('["' || replace(CASE WHEN instr(x.tstr, '-') > 1 THEN substr(x.tstr, 1, instr(x.tstr, '-') - 1) ELSE x.tstr END, '.', '","') || '"]')
    THEN '["' || replace(CASE WHEN instr(x.tstr, '-') > 1 THEN substr(x.tstr, 1, instr(x.tstr, '-') - 1) ELSE x.tstr END, '.', '","') || '"]' END AS tversion,
  -- getValueAsDouble: numbers, or strings that parse as numbers
  CASE WHEN x.vtype IN ('integer', 'real') THEN x.v ->> '$'
       WHEN x.vtype = 'bucket' THEN CAST(x.v AS INTEGER)
       -- a decimal number (Kotlin's toDoubleOrNull; not SQLite's JSON parser, which varies by version)
       WHEN x.vtype = 'text' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END END) GLOB '*[0-9]*' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END END) NOT GLOB '*[^0-9.]*' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END END) NOT GLOB '*.*.*'
           AND ((CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') + 1) END) IS NULL OR (ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') + 1) END), '+-') GLOB '[0-9]*' AND ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') + 1) END), '+-') NOT GLOB '*[^0-9]*'
             AND length((CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') + 1) END)) - length(ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END, instr(lower(CASE WHEN substr(trim(x.v ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.v ->> '$'), 2) ELSE trim(x.v ->> '$') END), 'e') + 1) END), '+-')) <= 1))
         THEN CAST(trim(x.v ->> '$') AS REAL) END AS vnum,
  CASE WHEN json_type(x.target) IN ('integer', 'real') THEN x.target ->> '$'
       WHEN json_type(x.target) = 'text' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END END) GLOB '*[0-9]*' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END END) NOT GLOB '*[^0-9.]*' AND (CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, 1, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') - 1) ELSE CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END END) NOT GLOB '*.*.*'
           AND ((CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') + 1) END) IS NULL OR (ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') + 1) END), '+-') GLOB '[0-9]*' AND ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') + 1) END), '+-') NOT GLOB '*[^0-9]*'
             AND length((CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') + 1) END)) - length(ltrim((CASE WHEN instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') > 0 THEN substr(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END, instr(lower(CASE WHEN substr(trim(x.target ->> '$'), 1, 1) IN ('+', '-') THEN substr(trim(x.target ->> '$'), 2) ELSE trim(x.target ->> '$') END), 'e') + 1) END), '+-')) <= 1))
         THEN CAST(trim(x.target ->> '$') AS REAL) END AS tnum,
  -- dates: epoch seconds or milliseconds (as numbers or digit strings), else yyyy-MM-ddTHH:mm:ss.SSSZ (UTC)
  CASE WHEN x.vtype IN ('integer', 'real') OR (x.vtype = 'text' AND (x.v ->> '$') GLOB '[0-9]*' AND NOT (x.v ->> '$') GLOB '*[^0-9]*')
         THEN CASE WHEN length(CAST(CAST(x.v ->> '$' AS INTEGER) AS TEXT)) < 11
                THEN CAST(x.v ->> '$' AS INTEGER) * 1000 ELSE CAST(x.v ->> '$' AS INTEGER) END
       WHEN x.vtype = 'text' AND (x.v ->> '$') GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z*'
         THEN CAST(round((julianday(substr(x.v ->> '$', 1, 23)) - 2440587.5) * 86400000.0) AS INTEGER) END AS vepoch,
  CASE WHEN json_type(x.target) IN ('integer', 'real') OR (json_type(x.target) = 'text' AND (x.target ->> '$') GLOB '[0-9]*' AND NOT (x.target ->> '$') GLOB '*[^0-9]*')
         THEN CASE WHEN length(CAST(CAST(x.target ->> '$' AS INTEGER) AS TEXT)) < 11
                THEN CAST(x.target ->> '$' AS INTEGER) * 1000 ELSE CAST(x.target ->> '$' AS INTEGER) END
       WHEN json_type(x.target) = 'text' AND (x.target ->> '$') GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z*'
         THEN CAST(round((julianday(substr(x.target ->> '$', 1, 23)) - 2440587.5) * 86400000.0) AS INTEGER) END AS tepoch
FROM (
  SELECT x.*,
  -- getValueAsString
    CASE x.vtype WHEN 'null' THEN NULL WHEN 'text' THEN x.v ->> '$' WHEN 'true' THEN 'true' WHEN 'false' THEN 'false'
      WHEN 'integer' THEN CAST(x.v ->> '$' AS TEXT) WHEN 'real' THEN CAST(x.v ->> '$' AS TEXT) ELSE x.v END AS vstr,
    CASE json_type(x.target) WHEN 'null' THEN NULL WHEN 'text' THEN x.target ->> '$' WHEN 'true' THEN 'true' WHEN 'false' THEN 'false'
      WHEN 'integer' THEN CAST(x.target ->> '$' AS TEXT) WHEN 'real' THEN CAST(x.target ->> '$' AS TEXT) ELSE x.target END AS tstr
  FROM (
    SELECT f.kind, f.spec_name, f.rule_idx, f.idx, f.type, f.operator, f.target,
      CASE
        WHEN f.type IN ('user_field', 'ip_based', 'ua_based') THEN f.user -> f.path
        WHEN f.type = 'environment_field' THEN coalesce(
          f.user -> ('$.statsigEnvironment."' || f.field || '"'),
          f.user -> ('$.statsigEnvironment."' || lower(f.field) || '"'))
        WHEN f.type = 'unit_id' THEN json_quote(f.unit_id)
        WHEN f.type = 'current_time' THEN json_quote(CAST(f.now_ms AS TEXT))
        WHEN f.type = 'user_bucket' THEN CAST(CAST(m.output AS INTEGER) % 1000 AS TEXT)
      END AS v,
      CASE
        WHEN f.type = 'user_bucket' THEN 'bucket'
        ELSE coalesce(json_type(CASE
          WHEN f.type IN ('user_field', 'ip_based', 'ua_based') THEN f.user -> f.path
          WHEN f.type = 'environment_field' THEN coalesce(
            f.user -> ('$.statsigEnvironment."' || f.field || '"'),
            f.user -> ('$.statsigEnvironment."' || lower(f.field) || '"'))
          WHEN f.type = 'unit_id' THEN json_quote(f.unit_id)
          WHEN f.type = 'current_time' THEN json_quote(CAST(f.now_ms AS TEXT)) END), 'null')
      END AS vtype
    FROM (SELECT c.*, u.user, u.now_ms FROM eval_cond_ctx AS c, eval_user AS u) AS f
      LEFT JOIN hash_memo AS m ON m.algo = 'bucket' AND m.input = f.bucket_input
    WHERE f.ref_gate IS NULL
  ) AS x
) AS x;


-- Loading the specs.
DELETE FROM dcs WHERE json_valid(:payload);
DELETE FROM dcs_spec WHERE json_valid(:payload);
DELETE FROM dcs_rule WHERE json_valid(:payload);
DELETE FROM dcs_condition WHERE json_valid(:payload);
DELETE FROM dcs_edge WHERE json_valid(:payload);
DELETE FROM dcs_param_store WHERE json_valid(:payload);
DELETE FROM eval_result;
INSERT INTO dcs (id, time, received_at)
  SELECT 1, coalesce(json_extract(:payload, '$.time'), 0), :received_at WHERE json_valid(:payload);
INSERT OR REPLACE INTO dcs_spec (kind, name, spec)
  SELECT k.kind, json_extract(s.value, '$.name'), s.value
  FROM (SELECT 'gate' AS kind, '$.feature_gates' AS path UNION ALL SELECT 'config', '$.dynamic_configs'
        UNION ALL SELECT 'layer', '$.layer_configs') AS k,
    json_each(CASE WHEN json_valid(:payload) THEN :payload ELSE '{}' END, k.path) AS s
  WHERE json_extract(s.value, '$.name') IS NOT NULL
  ORDER BY k.kind, s.key;
INSERT INTO dcs_rule (kind, spec_name, idx, rule)
  SELECT s.kind, s.name, r.key, r.value FROM dcs_spec AS s, json_each(s.spec, '$.rules') AS r
  WHERE json_valid(:payload);
INSERT INTO dcs_condition (kind, spec_name, rule_idx, idx, type, operator, field, target, id_type, salt, ref_gate)
  SELECT r.kind, r.spec_name, r.idx, c.key,
    lower(json_extract(c.value, '$.type')),
    json_extract(c.value, '$.operator'),
    json_extract(c.value, '$.field'),
    coalesce(c.value -> '$.targetValue', 'null'),
    json_extract(c.value, '$.idType'),
    CASE json_type(c.value, '$.additionalValues.salt')
      WHEN 'text' THEN json_extract(c.value, '$.additionalValues.salt')
      WHEN 'true' THEN 'true' WHEN 'false' THEN 'false'
      WHEN 'integer' THEN CAST(json_extract(c.value, '$.additionalValues.salt') AS TEXT)
      WHEN 'real' THEN CAST(json_extract(c.value, '$.additionalValues.salt') AS TEXT)
      WHEN 'object' THEN c.value -> '$.additionalValues.salt'
      WHEN 'array' THEN c.value -> '$.additionalValues.salt'
      ELSE 'null' END,
    CASE WHEN lower(json_extract(c.value, '$.type')) IN ('pass_gate', 'fail_gate') THEN
      CASE json_type(c.value, '$.targetValue')
        WHEN 'text' THEN json_extract(c.value, '$.targetValue')
        WHEN 'null' THEN '' WHEN 'true' THEN 'true' WHEN 'false' THEN 'false'
        ELSE coalesce(CAST(json_extract(c.value, '$.targetValue') AS TEXT), '') END END
  FROM dcs_rule AS r, json_each(r.rule, '$.conditions') AS c
  WHERE json_valid(:payload);
INSERT OR IGNORE INTO dcs_edge (kind, name, dep_kind, dep_name)
  SELECT kind, spec_name, 'gate', ref_gate FROM dcs_condition WHERE ref_gate IS NOT NULL
  UNION ALL
  SELECT kind, spec_name, 'config', json_extract(rule, '$.configDelegate') FROM dcs_rule
  WHERE json_extract(rule, '$.configDelegate') IS NOT NULL;
INSERT INTO dcs_param_store (name, parameters)
  SELECT key, coalesce(value -> '$.parameters', '{}')
  FROM json_each(CASE WHEN json_valid(:payload) THEN :payload ELSE '{}' END, '$.param_stores');


-- name: dcs_time
SELECT time, received_at FROM dcs;


-- name: dcs_param_store
SELECT s.parameters, d.time, d.received_at
FROM dcs AS d LEFT JOIN dcs_param_store AS s ON s.name = :name;


-- name: eval_begin
-- Starts evaluating :name (:kind 'gate' | 'config' | 'layer') for :user (JSON).
DELETE FROM eval_user;
DELETE FROM eval_needed;
DELETE FROM eval_cond;
DELETE FROM eval_result;
DELETE FROM hash_memo WHERE rowid <= (SELECT max(rowid) FROM hash_memo) - 2048;
-- regex answers are memoized per (pattern, value); keep the memo bounded
DELETE FROM regex_request WHERE (SELECT count(*) FROM regex_request) > 1000;
INSERT INTO eval_user (id, user, now_ms) SELECT 1, :user, now_ms FROM clock;
INSERT INTO eval_needed (kind, name)
  WITH RECURSIVE need(kind, name) AS (
    SELECT :kind, :name
    UNION
    SELECT e.dep_kind, e.dep_name FROM need JOIN dcs_edge AS e ON e.kind = need.kind AND e.name = need.name
  )
  SELECT kind, name FROM need;
-- Specs that do not exist evaluate as unrecognized.
INSERT INTO eval_result (kind, name, bool, value, rule_id, group_name, secondary, undelegated,
    is_experiment_group, is_active, config_delegate, explicit_parameters, unsupported, unrecognized)
  SELECT n.kind, n.name, 0, NULL, '', NULL, '[]', '[]', 0, 0, NULL, NULL, 0, 1
  FROM eval_needed AS n
  WHERE NOT EXISTS (SELECT 1 FROM dcs_spec AS s WHERE s.kind = n.kind AND s.name = n.name);
DELETE FROM eval_rule_ctx;
DELETE FROM eval_cond_ctx;
INSERT INTO eval_rule_ctx (kind, spec_name, idx, bucket_input)
  SELECT r.kind, r.spec_name, r.idx,
    coalesce(json_extract(s.spec, '$.salt'), 'null') || '.'
    || coalesce(json_extract(r.rule, '$.salt'), json_extract(r.rule, '$.id'), 'null') || '.'
    || coalesce(CASE WHEN lower(json_extract(r.rule, '$.idType')) <> 'userid' AND json_extract(r.rule, '$.idType') <> ''
         THEN coalesce(json_extract(u.user, '$.customIDs."' || json_extract(r.rule, '$.idType') || '"'),
                       json_extract(u.user, '$.customIDs."' || lower(json_extract(r.rule, '$.idType')) || '"'))
         ELSE json_extract(u.user, '$.userID') END, '')
  FROM eval_needed AS n
    JOIN dcs_rule AS r ON r.kind = n.kind AND r.spec_name = n.name
    JOIN dcs_spec AS s ON s.kind = r.kind AND s.name = r.spec_name,
    eval_user AS u;
INSERT INTO eval_cond_ctx (kind, spec_name, rule_idx, idx, type, operator, field, target, ref_gate,
    path, unit_id, bucket_input)
  SELECT kind, spec_name, rule_idx, idx, type, operator, field, target, ref_gate, path, unit_id, bucket_input
  FROM eval_cond_field;
INSERT INTO hash_input (algo, input)
  -- user_bucket conditions; rule pass percentages are hashed by eval_step, decisive rules only
  SELECT DISTINCT 'bucket', b.input FROM (
    SELECT bucket_input AS input FROM eval_cond_ctx WHERE bucket_input IS NOT NULL) AS b
  WHERE NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = 'bucket' AND m.input = b.input);
INSERT OR IGNORE INTO regex_request (pattern, value)
  SELECT tstr, vstr FROM eval_cond_value
  WHERE operator = 'str_matches' AND type IN ('user_field', 'ip_based', 'ua_based', 'environment_field',
      'unit_id', 'current_time', 'user_bucket')
    AND tstr IS NOT NULL AND vstr IS NOT NULL;
SELECT pattern, value FROM regex_request WHERE result IS NULL;


-- name: regex_result
UPDATE regex_request SET result = :result WHERE pattern = :pattern AND value = :value;


-- name: eval_conditions
-- Every condition that does not reference another gate. Unknown types and operators are
-- "unsupported": the spec then evaluates to its default value, as in the original.
INSERT INTO eval_cond (kind, spec_name, rule_idx, idx, pass, unsupported, exposures)
  SELECT c.kind, c.spec_name, c.rule_idx, c.idx,
    coalesce(CASE
      WHEN c.type = 'public' THEN 1
      WHEN c.type NOT IN ('user_field', 'ip_based', 'ua_based', 'environment_field', 'unit_id',
          'current_time', 'user_bucket') THEN 0
      WHEN c.operator IN ('gt', 'gte', 'lt', 'lte') THEN
        CASE WHEN c.vnum IS NULL OR c.tnum IS NULL THEN 0
          WHEN c.operator = 'gt' THEN c.vnum > c.tnum
          WHEN c.operator = 'gte' THEN c.vnum >= c.tnum
          WHEN c.operator = 'lt' THEN c.vnum < c.tnum
          ELSE c.vnum <= c.tnum END
      WHEN c.operator LIKE 'version\_%' ESCAPE '\' THEN
        CASE WHEN c.vversion IS NULL OR c.tversion IS NULL THEN 0 ELSE (
          -- first part that differs or is not an integer ('1.x'): a bad part fails the condition
          SELECT CASE WHEN bad THEN 0
            WHEN c.operator = 'version_gt' THEN cmp > 0
            WHEN c.operator = 'version_gte' THEN cmp >= 0
            WHEN c.operator = 'version_lt' THEN cmp < 0
            WHEN c.operator = 'version_lte' THEN cmp <= 0
            WHEN c.operator = 'version_eq' THEN cmp = 0
            WHEN c.operator = 'version_neq' THEN cmp <> 0 END
          FROM (
            SELECT i, NOT (ok_a AND ok_b) AS bad, (pa > pb) - (pa < pb) AS cmp
            FROM (
              SELECT CAST(i.key AS INTEGER) AS i,
                CAST(coalesce(json_extract(c.vversion, '$[' || i.key || ']'), '0') AS INTEGER) AS pa,
                CAST(coalesce(json_extract(c.tversion, '$[' || i.key || ']'), '0') AS INTEGER) AS pb,
                coalesce(json_extract(c.vversion, '$[' || i.key || ']'), '0') GLOB '[0-9+-]*'
                  AND ltrim(coalesce(json_extract(c.vversion, '$[' || i.key || ']'), '0'), '+-') <> ''
                  AND ltrim(substr(coalesce(json_extract(c.vversion, '$[' || i.key || ']'), '0'), 2), '0123456789') = '' AS ok_a,
                coalesce(json_extract(c.tversion, '$[' || i.key || ']'), '0') GLOB '[0-9+-]*'
                  AND ltrim(coalesce(json_extract(c.tversion, '$[' || i.key || ']'), '0'), '+-') <> ''
                  AND ltrim(substr(coalesce(json_extract(c.tversion, '$[' || i.key || ']'), '0'), 2), '0123456789') = '' AS ok_b
              FROM json_each(CASE WHEN json_array_length(c.vversion) >= json_array_length(c.tversion)
                THEN c.vversion ELSE c.tversion END) AS i)
            WHERE NOT (ok_a AND ok_b) OR pa <> pb
            UNION ALL
            SELECT 1e18, 0, 0
            ORDER BY i LIMIT 1)) END
      WHEN c.operator IN ('any', 'none', 'any_case_sensitive', 'none_case_sensitive',
          'str_starts_with_any', 'str_ends_with_any', 'str_contains_any', 'str_contains_none') THEN
        (c.operator IN ('none', 'none_case_sensitive', 'str_contains_none')) <> (
          c.vstr IS NOT NULL AND json_type(c.target) = 'array' AND EXISTS (
            SELECT 1 FROM (
              SELECT CASE t.type WHEN 'null' THEN NULL WHEN 'true' THEN 'true' WHEN 'false' THEN 'false'
                WHEN 'text' THEN t.value WHEN 'object' THEN t.value WHEN 'array' THEN t.value
                ELSE CAST(t.value AS TEXT) END AS s
              FROM json_each(c.target) AS t) AS t
            WHERE t.s IS NOT NULL AND CASE
              WHEN c.operator IN ('any', 'none') THEN lower(c.vstr) = lower(t.s)
              WHEN c.operator IN ('any_case_sensitive', 'none_case_sensitive') THEN c.vstr = t.s
              WHEN c.operator = 'str_starts_with_any' THEN substr(lower(c.vstr), 1, length(t.s)) = lower(t.s)
              WHEN c.operator = 'str_ends_with_any' THEN t.s = '' OR substr(lower(c.vstr), -length(t.s)) = lower(t.s)
              ELSE instr(lower(c.vstr), lower(t.s)) > 0 END))
      WHEN c.operator = 'str_matches' THEN
        CASE WHEN c.tstr IS NULL OR c.vstr IS NULL THEN 0
          ELSE (SELECT result = 1 FROM regex_request WHERE pattern = c.tstr AND value = c.vstr) END
      WHEN c.operator IN ('eq', 'neq') THEN
        (c.operator = 'neq') <> CASE
          WHEN c.vtype = 'null' OR json_type(c.target) = 'null' THEN c.vtype = 'null' AND json_type(c.target) = 'null'
          WHEN c.vtype = 'bucket' THEN 0
          ELSE c.vtype = json_type(c.target) AND c.v = c.target END
      WHEN c.operator IN ('before', 'after', 'on') THEN
        CASE WHEN c.vepoch IS NULL OR c.tepoch IS NULL THEN 0
          WHEN c.operator = 'before' THEN c.vepoch < c.tepoch
          WHEN c.operator = 'after' THEN c.vepoch > c.tepoch
          ELSE date(c.vepoch / 1000, 'unixepoch') = date(c.tepoch / 1000, 'unixepoch') END
      ELSE 0 END, 0),
    c.type NOT IN ('public', 'user_field', 'ip_based', 'ua_based', 'environment_field', 'unit_id',
        'current_time', 'user_bucket')
      OR (c.type <> 'public' AND coalesce(c.operator NOT IN ('gt', 'gte', 'lt', 'lte', 'version_gt',
        'version_gte', 'version_lt', 'version_lte', 'version_eq', 'version_neq', 'any', 'none',
        'any_case_sensitive', 'none_case_sensitive', 'str_starts_with_any', 'str_ends_with_any',
        'str_contains_any', 'str_contains_none', 'str_matches', 'eq', 'neq', 'before', 'after', 'on'), 1))
      OR (c.operator = 'str_matches' AND
        (SELECT result = -1 FROM regex_request WHERE pattern = c.tstr AND value = c.vstr) IS 1),
    '[]'
  FROM eval_cond_value AS c
  WHERE NOT EXISTS (SELECT 1 FROM eval_result AS r WHERE r.kind = c.kind AND r.name = c.spec_name);


-- name: eval_step
-- One round: gate conditions whose gate is evaluated, then specs whose conditions and delegates
-- are all evaluated. Returns how much is done, so the host can stop when a round adds nothing.
INSERT INTO eval_cond (kind, spec_name, rule_idx, idx, pass, unsupported, exposures)
  SELECT c.kind, c.spec_name, c.rule_idx, c.idx,
    CASE WHEN c.type = 'pass_gate' THEN r.bool ELSE NOT r.bool END,
    0,
    CASE WHEN substr(c.ref_gate, 1, 8) = 'segment:' THEN r.secondary
      ELSE json_insert(r.secondary, '$[#]', json_object(
        'gate', c.ref_gate,
        'gateValue', CASE WHEN r.bool THEN 'true' ELSE 'false' END,
        'ruleID', r.rule_id)) END
  FROM eval_cond_ctx AS c
    JOIN eval_result AS r ON r.kind = 'gate' AND r.name = c.ref_gate
  WHERE c.ref_gate IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM eval_cond AS x
      WHERE x.kind = c.kind AND x.spec_name = c.spec_name AND x.rule_idx = c.rule_idx AND x.idx = c.idx);
-- the pass-percentage hash of each decisive rule (only those: SHA-256 in SQL is not cheap)
INSERT INTO hash_input (algo, input)
  WITH
    -- needed specs not evaluated yet whose conditions and delegates all are
    ready AS (
      SELECT n.kind, n.name, sp.spec
      FROM eval_needed AS n JOIN dcs_spec AS sp ON sp.kind = n.kind AND sp.name = n.name
      WHERE NOT EXISTS (SELECT 1 FROM eval_result AS r WHERE r.kind = n.kind AND r.name = n.name)
        AND NOT EXISTS (SELECT 1 FROM eval_cond_ctx AS c
          WHERE c.kind = n.kind AND c.spec_name = n.name AND NOT EXISTS (
            SELECT 1 FROM eval_cond AS ec
            WHERE ec.kind = c.kind AND ec.spec_name = c.spec_name AND ec.rule_idx = c.rule_idx AND ec.idx = c.idx))
        AND NOT EXISTS (SELECT 1 FROM dcs_edge AS e
          WHERE e.kind = n.kind AND e.name = n.name AND e.dep_kind = 'config'
            AND NOT EXISTS (SELECT 1 FROM eval_result AS r WHERE r.kind = 'config' AND r.name = e.dep_name))
    ),
    -- per rule: do all its conditions pass, does any hit something unsupported
    rule_status AS (
      SELECT r.kind, r.spec_name, r.idx, r.rule,
        coalesce(min(ec.pass), 1) AS all_pass,
        coalesce(max(ec.unsupported), 0) AS any_unsupported
      FROM ready
        JOIN dcs_rule AS r ON r.kind = ready.kind AND r.spec_name = ready.name
        LEFT JOIN eval_cond AS ec ON ec.kind = r.kind AND ec.spec_name = r.spec_name AND ec.rule_idx = r.idx
      GROUP BY r.kind, r.spec_name, r.idx
    )
  SELECT DISTINCT 'bucket', b.bucket_input
  FROM (SELECT kind, spec_name, min(idx) AS idx FROM rule_status
        WHERE all_pass OR any_unsupported GROUP BY kind, spec_name) AS d
    JOIN eval_rule_ctx AS b ON b.kind = d.kind AND b.spec_name = d.spec_name AND b.idx = d.idx
  WHERE NOT EXISTS (SELECT 1 FROM hash_memo AS m WHERE m.algo = 'bucket' AND m.input = b.bucket_input);
INSERT INTO eval_result (kind, name, bool, value, rule_id, group_name, secondary, undelegated,
    is_experiment_group, is_active, config_delegate, explicit_parameters, unsupported, unrecognized)
  WITH
    -- needed specs not evaluated yet whose conditions and delegates all are
    ready AS (
      SELECT n.kind, n.name, sp.spec
      FROM eval_needed AS n JOIN dcs_spec AS sp ON sp.kind = n.kind AND sp.name = n.name
      WHERE NOT EXISTS (SELECT 1 FROM eval_result AS r WHERE r.kind = n.kind AND r.name = n.name)
        AND NOT EXISTS (SELECT 1 FROM eval_cond_ctx AS c
          WHERE c.kind = n.kind AND c.spec_name = n.name AND NOT EXISTS (
            SELECT 1 FROM eval_cond AS ec
            WHERE ec.kind = c.kind AND ec.spec_name = c.spec_name AND ec.rule_idx = c.rule_idx AND ec.idx = c.idx))
        AND NOT EXISTS (SELECT 1 FROM dcs_edge AS e
          WHERE e.kind = n.kind AND e.name = n.name AND e.dep_kind = 'config'
            AND NOT EXISTS (SELECT 1 FROM eval_result AS r WHERE r.kind = 'config' AND r.name = e.dep_name))
    ),
    -- per rule: do all its conditions pass, does any hit something unsupported
    rule_status AS (
      SELECT r.kind, r.spec_name, r.idx, r.rule,
        coalesce(min(ec.pass), 1) AS all_pass,
        coalesce(max(ec.unsupported), 0) AS any_unsupported
      FROM ready
        JOIN dcs_rule AS r ON r.kind = ready.kind AND r.spec_name = ready.name
        LEFT JOIN eval_cond AS ec ON ec.kind = r.kind AND ec.spec_name = r.spec_name AND ec.rule_idx = r.idx
      GROUP BY r.kind, r.spec_name, r.idx
    )
  SELECT
    t.kind, t.name,
    CASE WHEN t.outcome = 'delegate' THEN d.bool WHEN t.outcome = 'rule' THEN t.passed ELSE 0 END,
    CASE t.outcome
      WHEN 'disabled' THEN t.spec -> '$.defaultValue'
      WHEN 'unsupported' THEN NULL
      WHEN 'default' THEN t.spec -> '$.defaultValue'
      WHEN 'delegate' THEN d.value
      ELSE CASE WHEN t.passed THEN t.rule -> '$.returnValue' ELSE t.spec -> '$.defaultValue' END END,
    CASE t.outcome
      WHEN 'disabled' THEN 'disabled'
      WHEN 'unsupported' THEN 'default'
      WHEN 'default' THEN 'default'
      WHEN 'delegate' THEN d.rule_id
      ELSE json_extract(t.rule, '$.id') END,
    CASE t.outcome WHEN 'delegate' THEN d.group_name WHEN 'rule' THEN json_extract(t.rule, '$.groupName') END,
    CASE t.outcome
      WHEN 'disabled' THEN '[]'
      WHEN 'unsupported' THEN '[]'
      WHEN 'delegate' THEN (SELECT json_group_array(json(value)) FROM (
        SELECT value FROM (SELECT 0 AS part, key, value FROM json_each(t.exposures)
                           UNION ALL SELECT 1, key, value FROM json_each(d.secondary))
        ORDER BY part, key))
      ELSE t.exposures END,
    CASE t.outcome WHEN 'disabled' THEN '[]' WHEN 'unsupported' THEN '[]' ELSE t.exposures END,
    CASE t.outcome WHEN 'delegate' THEN d.is_experiment_group
      WHEN 'rule' THEN coalesce(json_extract(t.rule, '$.isExperimentGroup'), 0) ELSE 0 END,
    CASE t.outcome WHEN 'disabled' THEN 0 WHEN 'delegate' THEN d.is_active
      ELSE coalesce(json_extract(t.spec, '$.isActive'), 0) END,
    CASE WHEN t.outcome = 'delegate' THEN t.delegate END,
    CASE t.outcome
      WHEN 'delegate' THEN (SELECT spec -> '$.explicitParameters' FROM dcs_spec WHERE kind = 'config' AND name = t.delegate)
      WHEN 'unsupported' THEN coalesce(t.spec -> '$.explicitParameters', '[]') END,
    t.outcome = 'unsupported',
    0
  FROM (
    SELECT s.*,
      CASE WHEN NOT coalesce(json_extract(s.spec, '$.enabled'), 0) THEN 'disabled'
        WHEN s.decisive IS NULL THEN 'default'
        WHEN s.decisive_unsupported THEN 'unsupported'
        WHEN s.delegate IS NOT NULL AND EXISTS (SELECT 1 FROM dcs_spec WHERE kind = 'config' AND name = s.delegate)
          THEN 'delegate'
        ELSE 'rule' END AS outcome,
      -- pass percentage: bucket of salt.ruleSalt.unitID
      (SELECT CAST(m.output AS INTEGER) < CAST(coalesce(json_extract(s.rule, '$.passPercentage'), 0) * 100 AS INTEGER)
       FROM eval_rule_ctx AS b JOIN hash_memo AS m ON m.algo = 'bucket' AND m.input = b.bucket_input
       WHERE b.kind = s.kind AND b.spec_name = s.name AND b.idx = s.decisive) AS passed,
      -- exposures of every condition up to and including the decisive rule
      (SELECT json_group_array(json(value)) FROM (
        SELECT x.value FROM eval_cond AS ec, json_each(ec.exposures) AS x
        WHERE ec.kind = s.kind AND ec.spec_name = s.name AND ec.rule_idx <= coalesce(s.decisive, 1e18)
        ORDER BY ec.rule_idx, ec.idx, x.key)) AS exposures
    FROM (
      -- the decisive rule: the first that passes, or that hits an unsupported condition
      SELECT ready.kind, ready.name, ready.spec,
        rs.idx AS decisive, rs.any_unsupported AS decisive_unsupported, rs.rule,
        json_extract(rs.rule, '$.configDelegate') AS delegate
      FROM ready
        LEFT JOIN (SELECT kind, spec_name, min(idx) AS idx FROM rule_status
                   WHERE all_pass OR any_unsupported GROUP BY kind, spec_name) AS d
          ON d.kind = ready.kind AND d.spec_name = ready.name
        LEFT JOIN rule_status AS rs ON rs.kind = d.kind AND rs.spec_name = d.spec_name AND rs.idx = d.idx
    ) AS s
  ) AS t
    LEFT JOIN eval_result AS d ON d.kind = 'config' AND d.name = t.delegate;
SELECT (SELECT count(*) FROM eval_cond) + (SELECT count(*) FROM eval_result) AS progress;


-- name: eval_result
-- The evaluation of :kind/:name. unsupported is set if any spec involved hit an unsupported
-- condition (the host reports it, like the original).
SELECT r.bool, r.value, r.rule_id, r.group_name, r.secondary, r.undelegated,
  r.is_experiment_group, r.is_active, r.config_delegate, r.explicit_parameters, r.unrecognized,
  (SELECT max(unsupported) FROM eval_result) AS any_unsupported,
  -- the answer only changes with the specs and the user, unless a condition reads the clock
  NOT EXISTS (SELECT 1 FROM eval_cond_ctx WHERE type = 'current_time') AS cacheable,
  d.time AS lcut, d.received_at
FROM dcs AS d LEFT JOIN eval_result AS r ON r.kind = :kind AND r.name = :name;

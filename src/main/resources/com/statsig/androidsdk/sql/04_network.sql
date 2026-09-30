-- What goes over the wire, and which host it goes to (the logic of the original StatsigNetwork
-- and NetworkFallbackResolver; the HTTP calls themselves are made by the host).

-- name: schema

-- DNS lookups for fallback hosts are rate limited per endpoint (4 hours).
CREATE TEMP TABLE IF NOT EXISTS dns_cooldown (
  endpoint TEXT PRIMARY KEY,
  until INTEGER NOT NULL
);


-- name: initialize_request
-- Body of an initialize request for the session's user. :metadata is StatsigMetadata JSON,
-- :hash the requested name hashing ('djb2' | 'none'). With :poll = 1 the body also carries
-- lastSyncTimeForUser, as the polling loop does. sinceTime & co. are only sent when the values in
-- use were fetched for this very user. Also returns the user/scoped key the response belongs to.
SELECT json_patch('{}', json_object(
    'user', json(user),
    'statsigMetadata', json(:metadata),
    'sinceTime', CASE WHEN values_user_hash = user_hash THEN lcut END,
    'lastSyncTimeForUser', CASE WHEN :poll AND values_user_hash = user_hash THEN lcut END,
    'hash', :hash,
    'previousDerivedFields', json(CASE WHEN values_user_hash = user_hash THEN coalesce(derived_fields, '{}') ELSE '{}' END),
    'full_checksum', CASE WHEN values_user_hash = user_hash THEN full_checksum END)) AS body,
  user,
  scoped_key
FROM session;


-- name: should_compress
-- Whether a log_event request to :url is gzipped: always for Statsig's own host, and for the
-- app's custom/fallback urls only when the served sdk flag enable_log_event_compression is on.
SELECT CASE
    WHEN :disabled THEN 0
    WHEN substr(:url, 1, length(:default_api)) = :default_api THEN 1
    WHEN :url = :custom_url OR EXISTS (SELECT 1 FROM json_each(coalesce(:fallback_urls, '[]')) WHERE value = :url)
      THEN coalesce((SELECT json_type(sdk_flags, '$.enable_log_event_compression') = 'true' FROM session), 0)
    ELSE 0
  END AS compress;


-- name: fallback_url
-- The fallback url to use for :endpoint, if one is known and still valid. Entries expire after
-- 7 days, and are dropped when the app configured fallback urls that no longer include them.
-- Apps with a custom api and no fallback urls never use fallbacks.
DELETE FROM fallback_url
  WHERE endpoint = :endpoint
    AND NOT (:fallback_urls IS NULL AND :has_custom_url)
    AND (expires_at < (SELECT now_ms FROM clock)
      OR (:fallback_urls IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM json_each(:fallback_urls) WHERE rtrim(value, '/') = rtrim(fallback_url.url, '/'))));
SELECT url FROM fallback_url
  WHERE endpoint = :endpoint AND NOT (:fallback_urls IS NULL AND :has_custom_url);


-- name: fallback_url_worked
UPDATE fallback_url SET expires_at = (SELECT now_ms FROM clock) + 604800000 WHERE endpoint = :endpoint;


-- name: dns_query_allowed
-- Starts the per-endpoint DNS cooldown; returns 1 if a lookup may run now.
INSERT OR REPLACE INTO dns_cooldown (endpoint, until)
  SELECT :endpoint, now_ms + 14400000 FROM clock
  WHERE NOT EXISTS (SELECT 1 FROM dns_cooldown, clock WHERE endpoint = :endpoint AND until > now_ms);
SELECT changes() AS allowed;


-- name: fallback_url_candidates
-- Fallback urls from DNS TXT records such as 'i=example.org' (:dns_key 'i' for initialize, 'e'
-- for log_event), as a JSON array, in record order.
SELECT json_group_array('https://' || rtrim(host, '/') || coalesce(:path, 'null')) AS urls
FROM (
  SELECT CASE WHEN instr(rest, '=') > 0 THEN substr(rest, 1, instr(rest, '=') - 1) ELSE rest END AS host
  FROM (SELECT substr(value, length(:dns_key) + 2) AS rest, key FROM json_each(:records)
        WHERE substr(value, 1, length(:dns_key) + 1) = :dns_key || '=')
  ORDER BY key);


-- name: fallback_url_pick
-- After a domain failure: switch :endpoint to the first candidate that is neither the current
-- fallback nor one tried before. Returns 1 if a new url was stored.
INSERT OR REPLACE INTO fallback_url (endpoint, url, previous, expires_at)
  SELECT :endpoint, c.url,
    CASE WHEN f.url IS NULL THEN p.previous ELSE json_insert(p.previous, '$[#]', f.url) END,
    (SELECT now_ms FROM clock) + 604800000
  FROM (SELECT rtrim(value, '/') AS url, key FROM json_each(coalesce(:candidates, '[]'))) AS c
    LEFT JOIN fallback_url AS f ON f.endpoint = :endpoint
    JOIN (SELECT CASE WHEN json_array_length(coalesce(previous, '[]')) > 10 THEN '[]'
                 ELSE coalesce(previous, '[]') END AS previous
          FROM (SELECT (SELECT previous FROM fallback_url WHERE endpoint = :endpoint) AS previous)) AS p
  WHERE c.url NOT IN (SELECT value FROM json_each(coalesce(f.previous, '[]')))
    AND c.url IS NOT f.url
  ORDER BY c.key LIMIT 1;
SELECT changes() AS updated;

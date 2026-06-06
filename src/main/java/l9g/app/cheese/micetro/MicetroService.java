/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package l9g.app.cheese.micetro;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import l9g.app.cheese.token.BearerTokenConfig.BearerToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Business-logic layer for managing DNS and IPAM data in a Micetro
 * (Men&amp;Mice) server. It composes named JSON-RPC calls (via
 * {@link MicetroClient}) into higher-level operations used by the Spring Shell
 * commands: listing zones and ranges, searching records, scanning IP ranges,
 * and adding/removing A and ACME challenge TXT records.
 * <p>
 * Cross-cutting behaviors enforced here:
 * <ul>
 *   <li>the Micetro login session is cached in a Caffeine cache keyed by
 *       {@code CACHE_SESSION_KEY}, expiring after the configured
 *       {@code micetro.session-cache-ttl} seconds, so most calls reuse
 *       a single login;</li>
 *   <li>records this app creates are tagged with the comment
 *       {@code COMMENT_TAG} ({@code "l9g-cheese"}), and the tagged-only
 *       lookups/removals only touch the app's own records;</li>
 *   <li>mutating operations exposed to a bearer token first pass through
 *       {@link #zonePermitted(BearerToken, String)}.</li>
 * </ul>
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Service
public class MicetroService
{
  private final MicetroClient client;

  private final MicetroConfig micetroConfig;

  private final Cache<String, String> sessionCache;

  private static final String COMMENT_TAG = "l9g-cheese";

  private static final String CACHE_SESSION_KEY = "login";

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Creates the service and initializes the Caffeine session cache with a
   * write-expiry taken from the configured {@code micetro.session-cache-ttl}
   * seconds.
   *
   * @param client        the JSON-RPC transport used for all Micetro calls
   * @param micetroConfig the Micetro configuration (credentials and cache TTL)
   */
  public MicetroService(MicetroClient client, MicetroConfig micetroConfig)
  {
    this.client = client;
    this.micetroConfig = micetroConfig;
    this.sessionCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(micetroConfig.getSessionCacheTtl()))
      .build();
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Authorization gate: checks whether the given bearer token is permitted to
   * operate on a DNS zone. A token is permitted when its
   * {@code permittedZones} list contains the zone name (case-insensitive). A
   * denied attempt is logged at WARN level.
   *
   * @param token the authenticated bearer token (may be {@code null}, which is
   *             never permitted)
   * @param zone  the zone name being accessed (may be {@code null}, which is
   *             never permitted)
   * @return {@code true} if the token may access the zone, otherwise
   *         {@code false}
   */
  private boolean zonePermitted(BearerToken token, String zone)
  {
    boolean permitted = false;

    if(token != null && zone != null)
    {
      for(String z : token.getPermittedZones())
      {
        if(z.equalsIgnoreCase(zone))
        {
          permitted = true;
          break;
        }
      }
    }

    if( ! permitted)
    {
      log.warn("token '{}' not permitted to access zone '{}'", token, zone);
    }
    return permitted;
  }

  /**
   * Returns a valid Micetro session string, reusing the cached one when
   * present. On a cache miss it calls the {@code login} JSON-RPC method with the
   * configured server/credentials and caches the returned {@code session}
   * value for the configured {@code micetro.session-cache-ttl} seconds.
   *
   * @return the Micetro session token to pass as the {@code session} parameter
   *         of subsequent calls
   * @throws MicetroApiException if the {@code login} call returns a JSON-RPC
   *                            error
   */
  private String login()
  {
    log.debug("login");
    return sessionCache.get(CACHE_SESSION_KEY, _key ->
    {
      log.debug("login - cache miss");
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("server", micetroConfig.getServer());
      params.put("loginName", micetroConfig.getLoginName());
      params.put("password", micetroConfig.getPassword());
      params.put("unauthorizedAsForbidden", true);
      LinkedHashMap<String, Object> response = client.call("login", params);
      return (String)response.get("session");
    });
  }

  /**
   * Resolves a primary DNS zone name to the list of its object references. A
   * single zone name can map to several refs (e.g. across internal and external
   * views), so all matches are returned.
   *
   * @param zone    the primary zone name to look up
   * @param session the active Micetro session token
   * @return the list of matching zone refs, or {@code null} if no primary zone
   *         matched the name
   * @throws MicetroApiException if the {@code GetDNSZones} call returns a
   *                            JSON-RPC error
   */
  private List<String> findZoneRefs(String zone, String session)
  {
    List<String> result = null;

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("filter", "type=primary name=" + zone);
    params.put("limit", 500);
    params.put("offset", 0);
    params.put("sortBy", "natural");
    params.put("sortOrder", "Ascending");
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "GetDNSZones", params);

    List<Map<String, Object>> list = (List)response.get("dnsZones");

    if(list.size() > 0)
    {
      result = new ArrayList<>();
      for(Map<String, Object> map : list)
      {
        String ref = (String)map.get("ref");
        if(ref != null)
        {
          result.add(ref);
        }
      }
    }

    return result;
  }

  /**
   * Finds the refs of this app's own TXT records (filtered by type {@code TXT},
   * the {@code COMMENT_TAG} comment, and the given name) within a single zone
   * ref. Restricting to the comment tag ensures only records created by this
   * app are returned.
   *
   * @param session    the active Micetro session token
   * @param dnsZoneRef the zone ref to search within
   * @param name       the record name to match
   * @return the list of matching TXT record refs, or {@code null} if none
   *         matched
   * @throws MicetroApiException if the {@code GetDNSRecords} call returns a
   *                            JSON-RPC error
   */
  private List<String> findTxtDnsRecord(String session, String dnsZoneRef, String name)
  {
    List<String> result = null;

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("dnsZoneRef", dnsZoneRef);
    params.put("filter", "type=TXT comment=" + COMMENT_TAG + " name=" + name);
    params.put("includeMetaRecords", true);
    params.put("limit", 500);
    params.put("offset", 0);
    params.put("syncZone", false);
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "GetDNSRecords", params);

    List<Map<String, Object>> list = (List)response.get("dnsRecords");

    if(list.size() > 0)
    {
      result = new ArrayList<>();
      for(Map<String, Object> map : list)
      {
        String ref = (String)map.get("ref");
        if(ref != null)
        {
          result.add(ref);
        }
      }
    }

    return result;
  }

  /**
   * Creates a single DNS record in one zone ref via {@code AddDNSRecord}. The
   * record is tagged with {@code COMMENT_TAG} and enabled. A blank or
   * {@code null} {@code ttl} is omitted so Micetro applies the zone's default
   * TTL; naming-conflict checks are force-overridden.
   *
   * @param session    the active Micetro session token
   * @param dnsZoneRef the zone ref to create the record in
   * @param type       the DNS record type (e.g. {@code "A"}, {@code "TXT"})
   * @param name       the record name
   * @param data       the record rdata (e.g. an IP for A, the value for TXT)
   * @param ttl        the TTL as a string; blank/{@code null} applies the zone
   *                  default
   * @return the ref of the newly created record, or {@code null} if the call
   *         returned no response
   * @throws MicetroApiException if the {@code AddDNSRecord} call returns a
   *                            JSON-RPC error
   */
  private String addDnsRecord(
    String session, String dnsZoneRef, String type, String name,
    String data, String ttl)
  {
    Map<String, Object> dnsRecord = new LinkedHashMap<>();
    dnsRecord.put("name", name);
    dnsRecord.put("type", type);
    // an empty/absent ttl lets Micetro apply the zone's default TTL
    if(ttl != null && !ttl.isBlank())
    {
      dnsRecord.put("ttl", ttl);
    }
    dnsRecord.put("data", data);
    dnsRecord.put("comment", COMMENT_TAG);
    dnsRecord.put("enabled", true);
    dnsRecord.put("dnsZoneRef", dnsZoneRef);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("dnsRecord", dnsRecord);
    params.put("forceOverrideOfNamingConflictCheck", true);
    params.put("saveComment", "");
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "AddDNSRecord", params);

    log.debug("{}", response);
    // AddDNSRecord returns the ref of the newly created record
    return response != null ? (String)response.get("ref") : null;
  }

  /**
   * Convenience wrapper that adds a TXT record (with TTL {@code "0"}) to a
   * single zone ref by delegating to
   * {@link #addDnsRecord(String, String, String, String, String, String)}.
   *
   * @param session    the active Micetro session token
   * @param dnsZoneRef the zone ref to create the record in
   * @param name       the TXT record name
   * @param data       the TXT record value
   * @throws MicetroApiException if the underlying {@code AddDNSRecord} call
   *                            returns a JSON-RPC error
   */
  private void addTxtDnsRecord(String session, String dnsZoneRef, String name, String data)
  {
    addDnsRecord(session, dnsZoneRef, "TXT", name, data, "0");
  }

  /**
   * Queries this app's own A records (filtered by type {@code A}, the
   * {@code COMMENT_TAG} comment, and the given name) within a single zone ref.
   *
   * @param session    the active Micetro session token
   * @param dnsZoneRef the zone ref to search within
   * @param name       the record name to match
   * @return the matching A records as raw maps; an empty list if none matched
   * @throws MicetroApiException if the {@code GetDNSRecords} call returns a
   *                            JSON-RPC error
   */
  private List<Map<String, Object>> queryADnsRecords(
    String session, String dnsZoneRef, String name)
  {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("dnsZoneRef", dnsZoneRef);
    params.put("filter", "type=A comment=" + COMMENT_TAG + " name=" + name);
    params.put("includeMetaRecords", true);
    params.put("limit", 500);
    params.put("offset", 0);
    params.put("syncZone", false);
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "GetDNSRecords", params);

    List<Map<String, Object>> list = (List)response.get("dnsRecords");
    return list != null ? list : List.of();
  }

  /**
   * Remove Micetro objects by their raw references (e.g. DNS record refs
   * obtained via {@code search --show-refs}). This bypasses the
   * {@code COMMENT_TAG} guard that {@code remove-a} / {@code remove-txt} apply,
   * so it can delete any object the API user may delete — use with care.
   * Throws {@link MicetroApiException} if Micetro rejects the call outright.
   *
   * @param objRefs the raw object references to remove
   * @return the per-ref errors Micetro reported (empty = all removed). Micetro's
   *         {@code RemoveObjects} returns an {@code errors} array rather than
   *         throwing for individual refs.
   * @throws MicetroApiException if the {@code RemoveObjects} call (or the
   *                            implicit {@code login}) returns a JSON-RPC error
   */
  public List<Object> removeObjectsByRefs(List<String> objRefs)
  {
    log.debug("REMOVE OBJECTS: refs={}", objRefs);
    LinkedHashMap<String, Object> response = removeObjects(login(), objRefs);
    List<Object> errors = (List<Object>)response.get("errors");
    return errors != null ? errors : List.of();
  }

  /**
   * Enable or disable Micetro objects by their refs via {@code SetProperties}
   * ({@code properties: {enabled: true|false}}). Each ref is a separate call;
   * a per-ref {@link MicetroApiException} is collected rather than aborting.
   *
   * @param objRefs the object references to enable or disable
   * @param enabled {@code true} to enable the objects, {@code false} to disable
   * @return one {@code "ref: message"} string per ref that failed (empty = all
   *         succeeded)
   * @throws MicetroApiException if the implicit {@code login} returns a JSON-RPC
   *                            error (per-ref {@code SetProperties} failures are
   *                            collected, not thrown)
   */
  public List<String> setObjectsEnabled(List<String> objRefs, boolean enabled)
  {
    log.debug("SET ENABLED={}: refs={}", enabled, objRefs);
    String session = login();
    List<String> errors = new ArrayList<>();
    for(String ref : objRefs)
    {
      try
      {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("enabled", enabled);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ref", ref);
        params.put("properties", properties);
        params.put("saveComment", "");
        params.put("session", session);

        client.call("SetProperties", params);
      }
      catch(MicetroApiException e)
      {
        log.warn("SetProperties failed for ref {}: {}", ref, e.getMessage());
        errors.add(ref + ": " + e.getMessage());
      }
    }
    return errors;
  }

  /**
   * Issues a raw {@code RemoveObjects} call for the given refs.
   *
   * @param session the active Micetro session token
   * @param objRefs the object references to remove
   * @return the raw {@code RemoveObjects} response map (which may carry an
   *         {@code errors} array)
   * @throws MicetroApiException if the {@code RemoveObjects} call returns a
   *                            JSON-RPC error
   */
  private LinkedHashMap<String, Object> removeObjects(
    String session, List<String> objRefs)
  {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("objRefs", objRefs);
    params.put("saveComment", "");
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "RemoveObjects", params);

    log.debug("{}", response);
    return response;
  }

  /////////////////////////////////////////////////////////////////////////////

  
  /**
   * Authorized entry point to add an ACME challenge TXT record. Adds the record
   * only if the bearer token is permitted for the zone via
   * {@link #zonePermitted(BearerToken, String)}; otherwise it is a no-op.
   *
   * @param token the authenticated bearer token
   * @param zone  the target zone name
   * @param name  the TXT record name
   * @param data  the TXT record value
   * @throws MicetroApiException if the underlying add (or login) returns a
   *                            JSON-RPC error
   */
  public void addTxtRecords(
    BearerToken token, String zone, String name, String data)
  {
    log.debug("ADD: zone={}, name={}", zone, name);
    if(zonePermitted(token, zone))
    {
      addTxtRecords(zone, name, data);
    }
  }

  /**
   * Adds a TXT record to every ref of a zone (e.g. internal and external
   * views). Per-ref failures are collected as errors rather than aborting the
   * whole operation.
   *
   * @param zone the target zone name
   * @param name the TXT record name
   * @param data the TXT record value
   * @return an {@link AddResult} whose {@code succeeded} counts the refs the
   *         record was created in and {@code errors} lists per-ref failures;
   *         if the zone does not resolve, an empty result ({@code 0}, empty,
   *         empty) is returned. The {@code refs} list is always empty here.
   * @throws MicetroApiException if the login or zone lookup returns a JSON-RPC
   *                            error
   */
  public AddResult addTxtRecords(String zone, String name, String data)
  {
    log.debug("ADD: zone={}, name={}", zone, name);
    String session = login();
    List<String> zoneRefs = findZoneRefs(zone, session);
    log.debug("zoneRefs={}", zoneRefs);
    if(zoneRefs == null)
    {
      return new AddResult(0, List.of(), List.of());
    }
    int succeeded = 0;
    List<String> errors = new ArrayList<>();
    for(String ref : zoneRefs)
    {
      try
      {
        addTxtDnsRecord(session, ref, name, data);
        succeeded++;
      }
      catch(MicetroApiException e)
      {
        log.warn("add TXT failed for ref {}: {}", ref, e.getMessage());
        errors.add(ref + ": " + e.getMessage());
      }
    }
    return new AddResult(succeeded, errors, List.of());
  }

  /**
   * Adds an A record to every ref of a zone by delegating to
   * {@link #addRecords(String, String, String, String, String)} with type
   * {@code "A"}.
   *
   * @param zone the target zone name
   * @param name the A record name
   * @param ip   the IPv4 address to set as rdata
   * @param ttl  the TTL as a string; blank applies the zone default
   * @return the {@link AddResult} of the operation (succeeded count, per-ref
   *         errors, and refs of created records)
   * @throws MicetroApiException if the login or zone lookup returns a JSON-RPC
   *                            error
   */
  public AddResult addARecords(String zone, String name, String ip, String ttl)
  {
    return addRecords(zone, "A", name, ip, ttl);
  }

  /**
   * Add a DNS record of an arbitrary type to every ref of a zone (e.g. the
   * internal and external views). A blank {@code ttl} lets Micetro apply the
   * zone default. Records are tagged with {@code COMMENT_TAG} like all records
   * this app creates. Per-ref failures are collected as errors rather than
   * aborting the whole operation.
   *
   * @param zone the target zone name
   * @param type the DNS record type (e.g. {@code "A"})
   * @param name the record name
   * @param data the record rdata
   * @param ttl  the TTL as a string; blank applies the zone default
   * @return an {@link AddResult} with the succeeded count, per-ref error
   *         messages, and the refs of records actually created; if the zone does
   *         not resolve, an empty result ({@code 0}, empty, empty) is returned
   * @throws MicetroApiException if the login or zone lookup returns a JSON-RPC
   *                            error
   */
  public AddResult addRecords(
    String zone, String type, String name, String data, String ttl)
  {
    log.debug("ADD {}: zone={}, name={}, data={}", type, zone, name, data);
    String session = login();
    List<String> zoneRefs = findZoneRefs(zone, session);
    log.debug("zoneRefs={}", zoneRefs);
    if(zoneRefs == null)
    {
      return new AddResult(0, List.of(), List.of());
    }
    int succeeded = 0;
    List<String> errors = new ArrayList<>();
    List<String> refs = new ArrayList<>();
    for(String ref : zoneRefs)
    {
      try
      {
        String created = addDnsRecord(session, ref, type, name, data, ttl);
        if(created != null)
        {
          refs.add(created);
        }
        succeeded++;
      }
      catch(MicetroApiException e)
      {
        log.warn("add {} failed for ref {}: {}", type, ref, e.getMessage());
        errors.add(ref + ": " + e.getMessage());
      }
    }
    return new AddResult(succeeded, errors, refs);
  }

  /**
   * Previews which A records would be removed by {@link #removeARecords} without
   * deleting anything. Iterates all refs of the zone and lists this app's own
   * matching A records as human-readable lines.
   *
   * @param zone the target zone name
   * @param name the A record name to match
   * @return one description line per matching A record (name, type, data, ttl,
   *         ref); an empty list if the zone does not resolve or nothing matches
   * @throws MicetroApiException if the login, zone lookup, or record query
   *                            returns a JSON-RPC error
   */
  public List<String> dryRunRemoveARecords(String zone, String name)
  {
    log.debug("DRY-RUN REMOVE A: zone={}, name={}", zone, name);
    String session = login();
    List<String> zoneRefs = findZoneRefs(zone, session);
    if(zoneRefs == null)
    {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for(String ref : zoneRefs)
    {
      for(Map<String, Object> record : queryADnsRecords(session, ref, name))
      {
        lines.add(record.get("name") + "  A  " + record.get("data")
          + "  ttl=" + record.get("ttl") + "  ref=" + record.get("ref"));
      }
    }
    return lines;
  }

  /**
   * Removes this app's own A records with the given name across all refs of a
   * zone. Only records tagged with {@code COMMENT_TAG} are considered (via
   * {@link #queryADnsRecords}).
   *
   * @param zone the target zone name
   * @param name the A record name to match
   * @return the number of A record refs that were removed; {@code 0} if the
   *         zone does not resolve or nothing matched
   * @throws MicetroApiException if the login, zone lookup, record query, or
   *                            removal returns a JSON-RPC error
   */
  public int removeARecords(String zone, String name)
  {
    log.debug("REMOVE A: zone={}, name={}", zone, name);
    String session = login();
    List<String> zoneRefs = findZoneRefs(zone, session);
    log.debug("zoneRefs={}", zoneRefs);
    if(zoneRefs == null)
    {
      return 0;
    }
    List<String> objRefs = new ArrayList<>();
    for(String ref : zoneRefs)
    {
      for(Map<String, Object> record : queryADnsRecords(session, ref, name))
      {
        String objRef = (String)record.get("ref");
        if(objRef != null)
        {
          objRefs.add(objRef);
        }
      }
    }
    log.debug("objRefs={}", objRefs);
    if(!objRefs.isEmpty())
    {
      removeObjects(session, objRefs);
    }
    return objRefs.size();
  }

  /**
   * Orders record maps case-insensitively by {@code name}, then by {@code type},
   * for stable alphabetical listing.
   */
  private static final Comparator<Map<String, Object>> BY_NAME_TYPE =
    Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("name")),
      String.CASE_INSENSITIVE_ORDER)
      .thenComparing(r -> String.valueOf(r.get("type")));

  /**
   * Searches a zone for DNS records whose name starts with the given prefix
   * (the {@code "name=^"} anchored filter, so {@code "mailpit"} matches both
   * {@code "mailpit"} and {@code "mailpit.test"}). Iterates every ref/view of
   * the zone and tags each returned record with its zone {@code displayName}.
   *
   * @param zone the primary zone name to search
   * @param name the record-name prefix to match
   * @return the matching records as raw maps, each carrying a
   *         {@code displayName} entry; an empty list if the zone does not
   *         resolve
   * @throws MicetroApiException if the zone lookup or record query returns a
   *                            JSON-RPC error
   */
  public List<Map<String, Object>> searchRecords(String zone, String name)
  {
    log.debug("SEARCH: zone={}, name={}", zone, name);
    String session = login();

    Map<String, Object> zoneParams = new LinkedHashMap<>();
    zoneParams.put("filter", "type=primary name=" + zone);
    zoneParams.put("limit", 500);
    zoneParams.put("offset", 0);
    zoneParams.put("sortBy", "natural");
    zoneParams.put("sortOrder", "Ascending");
    zoneParams.put("session", session);

    LinkedHashMap<String, Object> zoneResponse = client.call(
      "GetDNSZones", zoneParams);
    List<Map<String, Object>> zones = (List)zoneResponse.get("dnsZones");

    List<Map<String, Object>> hits = new ArrayList<>();
    if(zones == null)
    {
      return hits;
    }

    for(Map<String, Object> zoneMap : zones)
    {
      String ref = (String)zoneMap.get("ref");
      String displayName = (String)zoneMap.get("displayName");
      if(ref == null)
      {
        continue;
      }

      Map<String, Object> params = new LinkedHashMap<>();
      params.put("dnsZoneRef", ref);
      // "^" anchors the match to the start of the name, so "mailpit"
      // matches both "mailpit" and "mailpit.test".
      params.put("filter", "name=^" + name);
      params.put("includeMetaRecords", true);
      params.put("limit", 500);
      params.put("offset", 0);
      params.put("syncZone", false);
      params.put("session", session);

      LinkedHashMap<String, Object> response = client.call(
        "GetDNSRecords", params);

      List<Map<String, Object>> list = (List)response.get("dnsRecords");
      if(list != null)
      {
        for(Map<String, Object> record : list)
        {
          record.put("displayName", displayName);
          hits.add(record);
        }
      }
    }
    return hits;
  }

  /**
   * Lists all real DNS records (meta records excluded) of a zone across every
   * ref/view. Each record is tagged with its zone {@code displayName}, and the
   * combined result is sorted by name, then type, then display name.
   *
   * @param zone the primary zone name to list
   * @return the zone's records as raw maps, each carrying a {@code displayName}
   *         entry, sorted; an empty list if the zone does not resolve
   * @throws MicetroApiException if the zone lookup or record query returns a
   *                            JSON-RPC error
   */
  public List<Map<String, Object>> listZoneRecords(String zone)
  {
    log.debug("LIST ZONE: zone={}", zone);
    String session = login();

    Map<String, Object> zoneParams = new LinkedHashMap<>();
    zoneParams.put("filter", "type=primary name=" + zone);
    zoneParams.put("limit", 500);
    zoneParams.put("offset", 0);
    zoneParams.put("sortBy", "natural");
    zoneParams.put("sortOrder", "Ascending");
    zoneParams.put("session", session);

    LinkedHashMap<String, Object> zoneResponse = client.call(
      "GetDNSZones", zoneParams);
    List<Map<String, Object>> zones = (List)zoneResponse.get("dnsZones");
    if(zones == null)
    {
      return List.of();
    }

    List<Map<String, Object>> hits = new ArrayList<>();
    for(Map<String, Object> zoneMap : zones)
    {
      String ref = (String)zoneMap.get("ref");
      String displayName = (String)zoneMap.get("displayName");
      if(ref == null)
      {
        continue;
      }

      Map<String, Object> params = new LinkedHashMap<>();
      params.put("dnsZoneRef", ref);
      params.put("includeMetaRecords", false);
      params.put("limit", 1000);
      params.put("offset", 0);
      params.put("syncZone", false);
      params.put("session", session);

      LinkedHashMap<String, Object> response = client.call(
        "GetDNSRecords", params);

      List<Map<String, Object>> list = (List)response.get("dnsRecords");
      if(list != null)
      {
        for(Map<String, Object> record : list)
        {
          record.put("displayName", displayName);
          hits.add(record);
        }
      }
    }

    hits.sort(BY_NAME_TYPE.thenComparing(
      r -> String.valueOf(r.get("displayName"))));
    return hits;
  }

  /**
   * Lists all addresses and records within an IPv4 net prefix. Resolves the
   * prefix to the most specific enclosing IPAM range and pages through
   * {@code GetIPAMRecords2}, collecting forward A records (one per view, with
   * zone-relative names), static DHCP reservations ({@code RESV}), and dynamic
   * DHCP leases ({@code LEASE}). If the prefix does not resolve to any IPAM
   * range, it falls back to {@link #rangeRecordsByZoneScan}. Results are sorted
   * numerically by IP address.
   *
   * @param net the IPv4 net prefix (e.g. {@code "141.41.99."})
   * @return the matching records as normalized maps
   *         ({@code name}/{@code type}/{@code data}/{@code ttl}/{@code comment}/
   *         {@code displayName}), sorted by IP
   * @throws MicetroApiException if any underlying JSON-RPC call returns an error
   */
  public List<Map<String, Object>> rangeRecords(String net)
  {
    log.debug("RANGE: net={}", net);
    String session = login();

    // Fast path: resolve the net prefix to the most specific IPAM range and let
    // GetIPAMRecords2 return every address of that range (with its A records for
    // all views) in one call. This replaces the old per-zone scan that fired one
    // GetDNSRecords call for every primary zone in the system.
    String rangeRef = findRangeRef(net, session);
    if(rangeRef == null)
    {
      log.debug("no IPAM range encloses '{}', falling back to per-zone scan", net);
      return rangeRecordsByZoneScan(net, session);
    }

    // map every primary zone ref -> displayName (one call), used both to label
    // the ZONE column and to render zone-relative record names.
    Map<String, String> zoneNames = zoneDisplayNames(session);

    List<Map<String, Object>> hits = new ArrayList<>();
    int offset = 0;
    final int limit = 500;
    while(true)
    {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("offset", offset);
      params.put("limit", limit);
      params.put("filter", "");
      params.put("rangeRef", rangeRef);
      params.put("session", session);

      LinkedHashMap<String, Object> response = client.call(
        "GetIPAMRecords2", params);

      List<Map<String, Object>> records = (List)response.get("ipamRecords");
      if(records == null || records.isEmpty())
      {
        break;
      }

      for(Map<String, Object> ipam : records)
      {
        // the enclosing range may be wider than the prefix the user typed
        // (e.g. a /23), so keep the original "starts with" semantics.
        String address = (String)ipam.get("address");
        boolean addrMatches = address != null && address.startsWith(net);

        // 1. DNS A records (forward records of this address, one per view)
        List<Map<String, Object>> dnsHosts = (List)ipam.get("dnsHosts");
        if(dnsHosts != null)
        {
          for(Map<String, Object> host : dnsHosts)
          {
            Map<String, Object> dnsRecord = (Map)host.get("dnsRecord");
            if(dnsRecord == null || !"A".equals(dnsRecord.get("type")))
            {
              continue;
            }
            String data = (String)dnsRecord.get("data");
            if(data == null || !data.startsWith(net))
            {
              continue;
            }
            String displayName = zoneNames.get(
              (String)dnsRecord.get("dnsZoneRef"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", relativeName(
              (String)dnsRecord.get("name"), displayName));
            out.put("type", "A");
            out.put("data", data);
            out.put("ttl", dnsRecord.get("ttl"));
            out.put("comment", dnsRecord.get("comment"));
            out.put("displayName", displayName);
            hits.add(out);
          }
        }

        if(!addrMatches)
        {
          continue;
        }

        // 2. static DHCP reservations (clientIdentifier is the MAC)
        List<Map<String, Object>> reservations =
          (List)ipam.get("dhcpReservations");
        if(reservations != null)
        {
          for(Map<String, Object> resv : reservations)
          {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", resv.get("name"));
            out.put("type", "RESV");
            out.put("data", address);
            out.put("ttl", "");
            out.put("comment", resv.get("clientIdentifier"));
            out.put("displayName", "");
            hits.add(out);
          }
        }

        // 3. dynamic DHCP leases handed out from the pool
        List<Map<String, Object>> leases = (List)ipam.get("dhcpLeases");
        if(leases != null)
        {
          for(Map<String, Object> lease : leases)
          {
            String mac = (String)lease.get("mac");
            String expiry = (String)lease.get("lease");
            StringBuilder comment = new StringBuilder();
            if(mac != null)
            {
              comment.append(mac);
            }
            if(expiry != null && !expiry.isBlank())
            {
              if(comment.length() > 0)
              {
                comment.append(' ');
              }
              comment.append("until ").append(expiry);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", lease.get("name"));
            out.put("type", "LEASE");
            out.put("data", address);
            out.put("ttl", "");
            out.put("comment", comment.toString());
            out.put("displayName", "");
            hits.add(out);
          }
        }
      }

      offset += records.size();
      Object total = response.get("totalResults");
      if(records.size() < limit
        || (total instanceof Number n && offset >= n.intValue()))
      {
        break;
      }
    }

    hits.sort((a, b) -> Long.compare(
      ipToLong((String)a.get("data")), ipToLong((String)b.get("data"))));
    return hits;
  }

  /**
   * Legacy per-zone scan: map every primary zone ref to its displayName and
   * query each zone for matching A records. Strictly zone-scoped because
   * GetDNSRecords rejects a missing dnsZoneRef and does not accept a dnsViewRef.
   * Used as a fallback for net prefixes that do not resolve to an IPAM range.
   *
   * @param net     the IPv4 net prefix to match against A record data
   *               ({@code data=^net})
   * @param session the active Micetro session token
   * @return the matching A records as raw maps, each tagged with its zone
   *         {@code displayName}, sorted numerically by IP
   * @throws MicetroApiException if the zone enumeration or any per-zone query
   *                            returns a JSON-RPC error
   */
  private List<Map<String, Object>> rangeRecordsByZoneScan(
    String net, String session)
  {
    Map<String, String> zoneNames = zoneDisplayNames(session);

    List<Map<String, Object>> hits = new ArrayList<>();
    for(String zoneRef : zoneNames.keySet())
    {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("dnsZoneRef", zoneRef);
      params.put("filter", "type=A data=^" + net);
      params.put("includeMetaRecords", true);
      params.put("limit", 1000);
      params.put("offset", 0);
      params.put("syncZone", false);
      params.put("session", session);

      LinkedHashMap<String, Object> response = client.call(
        "GetDNSRecords", params);

      List<Map<String, Object>> list = (List)response.get("dnsRecords");
      if(list != null)
      {
        for(Map<String, Object> record : list)
        {
          record.put("displayName", zoneNames.get(
            (String)record.get("dnsZoneRef")));
          hits.add(record);
        }
      }
    }

    hits.sort((a, b) -> Long.compare(
      ipToLong((String)a.get("data")), ipToLong((String)b.get("data"))));
    return hits;
  }

  /**
   * Builds a map of every primary DNS zone ref to its display name in a single
   * {@code GetDNSZones} call, used to label and resolve zone-relative names.
   *
   * @param session the active Micetro session token
   * @return a map from zone {@code ref} to {@code displayName}; empty if no
   *         zones are returned
   * @throws MicetroApiException if the {@code GetDNSZones} call returns a
   *                            JSON-RPC error
   */
  private Map<String, String> zoneDisplayNames(String session)
  {
    Map<String, String> zoneNames = new LinkedHashMap<>();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("filter", "type=primary");
    params.put("limit", 1000);
    params.put("offset", 0);
    params.put("session", session);
    LinkedHashMap<String, Object> response = client.call("GetDNSZones", params);
    List<Map<String, Object>> zones = (List)response.get("dnsZones");
    if(zones != null)
    {
      for(Map<String, Object> z : zones)
      {
        zoneNames.put((String)z.get("ref"), (String)z.get("displayName"));
      }
    }
    return zoneNames;
  }

  /**
   * Resolve an IPv4 net prefix (e.g. "141.41.99.") to the ref of the most
   * specific (smallest) IPAM range that contains it, or null if none matches.
   * Enumerates ranges via {@code GetRanges} and picks the smallest IPv4 range
   * (non-IPv4 ranges are skipped) whose {@code from}..{@code to} span encloses
   * the prefix base address.
   *
   * @param net     the IPv4 net prefix to resolve (e.g. {@code "141.41.99."})
   * @param session the active Micetro session token
   * @return the ref of the most specific enclosing IPAM range, or {@code null}
   *         if the prefix is invalid or no range encloses it
   * @throws MicetroApiException if the {@code GetRanges} call returns a JSON-RPC
   *                            error
   */
  private String findRangeRef(String net, String session)
  {
    long base = ipToLong(prefixToBaseIp(net));
    if(base == Long.MAX_VALUE)
    {
      return null;
    }

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("limit", 1000);
    params.put("offset", 0);
    params.put("session", session);
    LinkedHashMap<String, Object> response = client.call("GetRanges", params);
    List<Map<String, Object>> ranges = (List)response.get("ranges");
    if(ranges == null)
    {
      return null;
    }

    String bestRef = null;
    long bestSpan = Long.MAX_VALUE;
    for(Map<String, Object> r : ranges)
    {
      long from = ipToLong((String)r.get("from"));
      long to = ipToLong((String)r.get("to"));
      if(from == Long.MAX_VALUE || to == Long.MAX_VALUE)
      {
        continue;   // non-IPv4 (e.g. IPv6) range
      }
      if(from <= base && base <= to && (to - from) < bestSpan)
      {
        bestSpan = to - from;
        bestRef = (String)r.get("ref");
      }
    }
    return bestRef;
  }

  /**
   * Turn a net prefix into its base IPv4 address by padding the missing octets
   * with "0": "141.41.99." -> "141.41.99.0", "10." -> "10.0.0.0". Returns null
   * if the prefix is not a valid 1-4 octet IPv4 fragment.
   *
   * @param net the net prefix to expand (a trailing dot is tolerated)
   * @return the four-octet base IPv4 address string, or {@code null} if
   *         {@code net} is {@code null}, empty, or has more than four octets
   */
  private static String prefixToBaseIp(String net)
  {
    if(net == null)
    {
      return null;
    }
    String trimmed = net.endsWith(".")
      ? net.substring(0, net.length() - 1) : net;
    if(trimmed.isEmpty())
    {
      return null;
    }
    String[] parts = trimmed.split("\\.");
    if(parts.length == 0 || parts.length > 4)
    {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for(int i = 0; i < 4; i++)
    {
      if(i > 0)
      {
        sb.append('.');
      }
      sb.append(i < parts.length ? parts[i] : "0");
    }
    return sb.toString();
  }

  /**
   * Render the zone-relative record name from an IPAM dnsRecord name (a fully
   * qualified name with a trailing " [view]" tag, e.g.
   * "host.network.sonia.de. [external]") given the zone displayName
   * ("sonia.de. (external)"). Falls back to the bare FQDN when the zone is
   * unknown, and yields "@" for the zone apex.
   *
   * @param fullName    the IPAM dnsRecord name, possibly with a trailing
   *                   {@code " [view]"} tag (may be {@code null})
   * @param displayName the zone display name, possibly with a trailing
   *                   {@code " (view)"} suffix (may be {@code null})
   * @return the zone-relative record name; {@code "@"} for the apex, the bare
   *         FQDN when the zone is unknown, or an empty string when
   *         {@code fullName} is {@code null}
   */
  private static String relativeName(String fullName, String displayName)
  {
    if(fullName == null)
    {
      return "";
    }
    // drop the trailing " [external]" / " [internal]" view tag
    String name = fullName.replaceFirst("\\s*\\[[^\\]]*\\]\\s*$", "").trim();
    if(displayName == null)
    {
      return name;
    }
    // strip the " (view)" suffix from the displayName to get the zone FQDN
    String zone = displayName.replaceFirst("\\s*\\([^)]*\\)\\s*$", "").trim();
    if(name.equalsIgnoreCase(zone))
    {
      return "@";
    }
    if(zone.length() > 0 && name.toLowerCase().endsWith("." + zone.toLowerCase()))
    {
      return name.substring(0, name.length() - zone.length() - 1);
    }
    return name;
  }

  /**
   * Converts a dotted-quad IPv4 address into its numeric value for ordering and
   * range comparisons. Invalid or non-IPv4 input sorts last.
   *
   * @param ip the IPv4 address string (e.g. {@code "141.41.99.7"})
   * @return the address as an unsigned 32-bit value packed into a {@code long},
   *         or {@link Long#MAX_VALUE} if {@code ip} is {@code null}, not four
   *         octets, or not numeric
   */
  private static long ipToLong(String ip)
  {
    if(ip == null)
    {
      return Long.MAX_VALUE;
    }
    String[] octets = ip.split("\\.");
    if(octets.length != 4)
    {
      return Long.MAX_VALUE;
    }
    try
    {
      long value = 0;
      for(String octet : octets)
      {
        value = (value << 8) + Integer.parseInt(octet.trim());
      }
      return value;
    }
    catch(NumberFormatException e)
    {
      return Long.MAX_VALUE;
    }
  }

  /**
   * Lists IPAM ranges via {@code GetRanges}, optionally narrowed by a Micetro
   * filter expression.
   *
   * @param filter an optional Micetro filter expression; ignored when
   *              {@code null} or blank
   * @return the matching ranges as raw maps; an empty list if none are returned
   * @throws MicetroApiException if the {@code GetRanges} call returns a JSON-RPC
   *                            error
   */
  public List<Map<String, Object>> listRanges(String filter)
  {
    log.debug("LIST RANGES: filter={}", filter);
    String session = login();

    Map<String, Object> params = new LinkedHashMap<>();
    if(filter != null && !filter.isBlank())
    {
      params.put("filter", filter);
    }
    params.put("limit", 500);
    params.put("offset", 0);
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call("GetRanges", params);

    List<Map<String, Object>> list = (List)response.get("ranges");
    return list != null ? list : List.of();
  }

  /**
   * Returns the display names of all primary DNS zones, sorted naturally and
   * ascending by Micetro.
   *
   * @return the list of primary zone display names; empty if none are returned
   * @throws MicetroApiException if the login or {@code GetDNSZones} call returns
   *                            a JSON-RPC error
   */
  public List<String> listZoneDisplayNames()
  {
    log.debug("LIST ZONES");
    String session = login();

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("filter", "type=primary");
    params.put("limit", 500);
    params.put("offset", 0);
    params.put("sortBy", "natural");
    params.put("sortOrder", "Ascending");
    params.put("session", session);

    LinkedHashMap<String, Object> response = client.call(
      "GetDNSZones", params);

    List<Map<String, Object>> list = (List)response.get("dnsZones");

    List<String> result = new ArrayList<>();
    if(list != null)
    {
      for(Map<String, Object> map : list)
      {
        String displayName = (String)map.get("displayName");
        if(displayName != null)
        {
          result.add(displayName);
        }
      }
    }
    return result;
  }

  /**
   * Authorized entry point to remove an ACME challenge TXT record. Removes the
   * record only if the bearer token is permitted for the zone via
   * {@link #zonePermitted(BearerToken, String)}; otherwise it is a no-op.
   *
   * @param token the authenticated bearer token
   * @param zone  the target zone name
   * @param name  the TXT record name to remove
   * @throws MicetroApiException if the underlying removal (or login) returns a
   *                            JSON-RPC error
   */
  public void removeTxtRecords(
    BearerToken token, String zone, String name)
  {
    log.debug("REMOVE: zone={}, name={}", zone, name);
    if(zonePermitted(token, zone))
    {
      removeTxtRecords(zone, name);
    }
  }

  /**
   * Removes this app's own TXT records with the given name across all refs of a
   * zone. Only records tagged with {@code COMMENT_TAG} are considered (via
   * {@link #findTxtDnsRecord}).
   *
   * @param zone the target zone name
   * @param name the TXT record name to match
   * @return the number of TXT record refs that were removed; {@code 0} if the
   *         zone does not resolve or nothing matched
   * @throws MicetroApiException if the login, zone lookup, record query, or
   *                            removal returns a JSON-RPC error
   */
  public int removeTxtRecords(String zone, String name)
  {
    log.debug("REMOVE: zone={}, name={}", zone, name);
    String session = login();
    List<String> zoneRefs = findZoneRefs(zone, session);
    log.debug("zoneRefs={}", zoneRefs);
    if(zoneRefs == null)
    {
      return 0;
    }
    List<String> objRefs = new ArrayList<>();
    for(String ref : zoneRefs)
    {
      List<String> records = findTxtDnsRecord(session, ref, name);
      if(records != null)
      {
        objRefs.addAll(records);
      }
    }
    log.debug("objRefs={}", objRefs);
    if(!objRefs.isEmpty())
    {
      removeObjects(session, objRefs);
    }
    return objRefs.size();
  }

  /**
   * Outcome of an add operation that spans multiple zone refs (e.g. internal
   * and external views). {@code succeeded} counts the refs the record was
   * created in; {@code errors} holds one message per ref that rejected it
   * (e.g. a view where the IP is outside the assigned range); {@code refs} holds
   * the object references of the records actually created (from AddDNSRecord).
   *
   * @param succeeded the number of zone refs the record was created in
   * @param errors    one message per ref that rejected the record (empty = none)
   * @param refs      the object references of the records actually created
   */
  public static record AddResult(
    int succeeded, List<String> errors, List<String> refs)
  {
  }

}

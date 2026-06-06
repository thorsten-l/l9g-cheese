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
  
  public MicetroService(MicetroClient client, MicetroConfig micetroConfig)
  {
    this.client = client;
    this.micetroConfig = micetroConfig;
    this.sessionCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(micetroConfig.getSessionCacheTtl()))
      .build();
  }

  /////////////////////////////////////////////////////////////////////////////

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

  private void addTxtDnsRecord(String session, String dnsZoneRef, String name, String data)
  {
    addDnsRecord(session, dnsZoneRef, "TXT", name, data, "0");
  }

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
   * @return the per-ref errors Micetro reported (empty = all removed). Micetro's
   *         {@code RemoveObjects} returns an {@code errors} array rather than
   *         throwing for individual refs.
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
   * @return one error message per ref that failed (empty = all succeeded)
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

  
  public void addTxtRecords(
    BearerToken token, String zone, String name, String data)
  {
    log.debug("ADD: zone={}, name={}", zone, name);
    if(zonePermitted(token, zone))
    {
      addTxtRecords(zone, name, data);
    }
  }

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

  public AddResult addARecords(String zone, String name, String ip, String ttl)
  {
    return addRecords(zone, "A", name, ip, ttl);
  }

  /**
   * Add a DNS record of an arbitrary type to every ref of a zone (e.g. the
   * internal and external views). A blank {@code ttl} lets Micetro apply the
   * zone default. Records are tagged with {@code COMMENT_TAG} like all records
   * this app creates.
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

  private static final Comparator<Map<String, Object>> BY_NAME_TYPE =
    Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("name")),
      String.CASE_INSENSITIVE_ORDER)
      .thenComparing(r -> String.valueOf(r.get("type")));

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

  public void removeTxtRecords(
    BearerToken token, String zone, String name)
  {
    log.debug("REMOVE: zone={}, name={}", zone, name);
    if(zonePermitted(token, zone))
    {
      removeTxtRecords(zone, name);
    }
  }

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
   */
  public static record AddResult(
    int succeeded, List<String> errors, List<String> refs)
  {
  }

}

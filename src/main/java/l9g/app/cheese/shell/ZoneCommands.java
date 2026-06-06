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
package l9g.app.cheese.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import l9g.app.cheese.micetro.MicetroService;
import l9g.app.cheese.micetro.MicetroService.AddResult;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Component
public class ZoneCommands
{
  private final MicetroService micetroService;

  public ZoneCommands(MicetroService micetroService)
  {
    this.micetroService = micetroService;
  }

  @Command(name = "list-records",
           group = "Micetro Commands",
           description = "List all DNS records in a zone, sorted alphabetically by name")
  public String listRecords(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "separate",
            description = "Output each matching zone (view) in its own block",
            defaultValue = "false") boolean separate)
  {
    zone = normalizeZone(zone);
    log.debug("list-records: zone={}, separate={}", zone, separate);
    List<Map<String, Object>> records = micetroService.listZoneRecords(zone);

    if(records.isEmpty())
    {
      return "No records found in zone " + zone;
    }

    if( ! separate)
    {
      return renderRecordTable(records, true);
    }

    // group by zone displayName (records are already name-sorted, so each group
    // keeps its alphabetical order)
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for(Map<String, Object> record : records)
    {
      groups.computeIfAbsent(String.valueOf(record.get("displayName")),
        k -> new ArrayList<>()).add(record);
    }

    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet())
    {
      if(sb.length() > 0)
      {
        sb.append(System.lineSeparator()).append(System.lineSeparator());
      }
      sb.append("=== ").append(group.getKey()).append(" ===")
        .append(System.lineSeparator());
      sb.append(renderRecordTable(group.getValue(), false));
    }
    return sb.toString();
  }

  @Command(name = "search",
           group = "Micetro Commands",
           description = "Search DNS records by name across all refs of a zone (lists type and data)")
  public String search(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name to match",
            required = true) String name)
  {
    zone = normalizeZone(zone);
    log.debug("search: zone={}, name={}", zone, name);
    List<Map<String, Object>> records = micetroService.searchRecords(zone, name);

    if(records.isEmpty())
    {
      return "No records found for name '" + name + "' in zone " + zone;
    }

    return renderRecordTable(records, true);
  }

  /////////////////////////////////////////////////////////////////////////////
  // table rendering for DNS record listings (search / range / list-records)

  private static final int MAX_CELL_WIDTH = 60;

  private static String cell(Object value)
  {
    if(value == null)
    {
      return "";
    }
    String text = String.valueOf(value).replace('\t', ' ')
      .replace('\n', ' ').replace('\r', ' ');
    if(text.length() > MAX_CELL_WIDTH)
    {
      text = text.substring(0, MAX_CELL_WIDTH - 1) + "…";
    }
    return text;
  }

  private static String renderRecordTable(
    List<Map<String, Object>> records, boolean withZone)
  {
    String[] headers = withZone
      ? new String[]
      {
        "NAME", "TYPE", "DATA", "TTL", "COMMENT", "ZONE"
      }
      : new String[]
      {
        "NAME", "TYPE", "DATA", "TTL", "COMMENT"
      };

    List<String[]> rows = new ArrayList<>();
    for(Map<String, Object> r : records)
    {
      String[] row = new String[headers.length];
      row[0] = cell(r.get("name"));
      row[1] = cell(r.get("type"));
      row[2] = cell(r.get("data"));
      row[3] = cell(r.get("ttl"));
      row[4] = cell(r.get("comment"));
      if(withZone)
      {
        row[5] = cell(r.get("displayName"));
      }
      rows.add(row);
    }

    // the TYPE column (index 1) gets a per-type background colour
    return renderStripedTable(headers, rows, 1);
  }

  /**
   * Render a zebra-striped table: bold headers, alternating light-grey row
   * backgrounds with black text. When {@code typeColumn >= 0}, that column is
   * tinted per {@link #typeBackground} (used by the DNS record tables); pass
   * {@code -1} for a plain striped table (list-zones / list-ranges).
   */
  private static String renderStripedTable(
    String[] headers, List<String[]> rows, int typeColumn)
  {
    int[] width = new int[headers.length];
    for(int i = 0; i < headers.length; i++)
    {
      width[i] = headers[i].length();
    }
    for(String[] row : rows)
    {
      for(int i = 0; i < row.length; i++)
      {
        width[i] = Math.max(width[i], row[i].length());
      }
    }

    AttributedStringBuilder asb = new AttributedStringBuilder();

    // bold header
    AttributedStyle bold = AttributedStyle.DEFAULT.bold();
    for(int i = 0; i < headers.length; i++)
    {
      asb.style(bold).append(pad(headers[i], width[i]));
      if(i < headers.length - 1)
      {
        asb.style(bold).append("  ");
      }
    }
    asb.style(AttributedStyle.DEFAULT).append(System.lineSeparator());

    // zebra-striped data rows with black text
    for(int idx = 0; idx < rows.size(); idx++)
    {
      int zebra = (idx % 2 == 0) ? ROW_BG_A : ROW_BG_B;
      AttributedStyle base = AttributedStyle.DEFAULT
        .foreground(AttributedStyle.BLACK).background(zebra);
      String[] row = rows.get(idx);
      for(int i = 0; i < row.length; i++)
      {
        AttributedStyle cellStyle = base;
        if(i == typeColumn)
        {
          int typeBg = typeBackground(row[i]);
          if(typeBg >= 0)
          {
            cellStyle = AttributedStyle.DEFAULT
              .foreground(AttributedStyle.BLACK).background(typeBg);
          }
        }
        asb.style(cellStyle).append(pad(row[i], width[i]));
        if(i < row.length - 1)
        {
          asb.style(base).append("  ");
        }
      }
      asb.style(AttributedStyle.DEFAULT).append(System.lineSeparator());
    }
    return asb.toAnsi().stripTrailing();
  }

  private static String pad(String s, int width)
  {
    return s + " ".repeat(width - s.length());
  }

  // two light grey tones for the zebra striping (256-colour palette)
  private static final int ROW_BG_A = 255;

  private static final int ROW_BG_B = 252;

  // light background colours per record type (256-colour palette), -1 = none
  private static int typeBackground(String type)
  {
    return switch(type)
    {
      case "A" ->
        153;   // light blue
      case "AAAA" ->
        117;   // cyan blue
      case "CNAME" ->
        218;   // pink
      case "TXT" ->
        151;   // light green
      case "NS" ->
        187;   // khaki
      case "MX" ->
        223;   // light peach
      case "SOA" ->
        250;   // grey
      case "CAA" ->
        224;   // light red
      case "PTR" ->
        159;   // light cyan
      case "SRV" ->
        189;   // lavender
      case "RESV" ->
        216;   // light orange (DHCP reservation)
      case "LEASE" ->
        230;   // pale yellow (DHCP lease)
      default ->
        -1;
    };
  }

  @Command(name = "list-ipaddrs",
           group = "Micetro Commands",
           description = "List A records whose IP starts with the given net prefix, sorted by IP")
  public String listIpaddrs(
    @Option(longName = "net",
            description = "IPv4 prefix the address must start with (e.g. 141.41.2.)",
            required = true) String net)
  {
    log.debug("list-ipaddrs: net={}", net);
    List<Map<String, Object>> records = micetroService.rangeRecords(net);

    if(records.isEmpty())
    {
      return "No A records found starting with '" + net + "'";
    }

    return renderRecordTable(records, true);
  }

  @Command(name = "list-ranges",
           group = "Micetro Commands",
           description = "List the IP ranges the configured API user is allowed to manage")
  public String listRanges(
    @Option(longName = "filter",
            description = "Optional Micetro range filter (e.g. 'type!=Container' or 'name=^10.230')",
            defaultValue = "type!=Container") String filter)
  {
    log.debug("list-ranges: filter={}", filter);
    List<Map<String, Object>> ranges = micetroService.listRanges(filter);

    if(ranges.isEmpty())
    {
      return "No ranges visible to this account"
        + (filter == null || filter.isBlank() ? "" : " (filter: " + filter + ")")
        + ".";
    }

    List<String[]> rows = new ArrayList<>();
    for(Map<String, Object> range : ranges)
    {
      rows.add(new String[]
      {
        cell(range.get("name")), cell(range.get("from")), cell(range.get("to"))
      });
    }
    return renderStripedTable(new String[]
    {
      "NAME", "FROM", "TO"
    }, rows, -1);
  }

  @Command(name = "list-zones",
           group = "Micetro Commands",
           description = "List the display names of all primary DNS zones")
  public String listZones()
  {
    log.debug("list-zones");
    List<String> displayNames = micetroService.listZoneDisplayNames();

    if(displayNames.isEmpty())
    {
      return "No zones found.";
    }

    List<String[]> rows = new ArrayList<>();
    for(String displayName : displayNames)
    {
      rows.add(new String[]
      {
        cell(displayName)
      });
    }
    return renderStripedTable(new String[]
    {
      "ZONE"
    }, rows, -1);
  }

  @Command(name = "add-a",
           group = "Micetro Commands",
           description = "Add an A DNS record to all refs of a zone")
  public String addA(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name (e.g. host.example.de.)",
            required = true) String name,
    @Option(longName = "ip",
            description = "IPv4 address",
            required = true) String ip,
    @Option(longName = "ttl",
            description = "TTL in seconds (e.g. 300)",
            required = true) String ttl)
  {
    zone = normalizeZone(zone);
    log.debug("add-a: zone={}, name={}, ip={}", zone, name, ip);
    return formatAddResult(
      "A", zone, micetroService.addARecords(zone, name, ip, ttl));
  }

  @Command(name = "remove-a",
           group = "Micetro Commands",
           description = "Remove all A DNS records (tagged l9g-cheese) for a name from all refs of a zone")
  public String removeA(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name (e.g. host.example.de.)",
            required = true) String name,
    @Option(longName = "dry",
            description = "Show records that would be deleted without actually deleting them",
            defaultValue = "false") boolean dry)
  {
    zone = normalizeZone(zone);
    log.debug("remove-a: zone={}, name={}, dry={}", zone, name, dry);
    if(dry)
    {
      List<String> records = micetroService.dryRunRemoveARecords(zone, name);
      if(records.isEmpty())
      {
        return "No matching A records found for name '" + name + "' in zone " + zone;
      }
      return "[DRY-RUN] Would delete " + records.size() + " record(s):"
        + System.lineSeparator()
        + String.join(System.lineSeparator(), records);
    }
    int count = micetroService.removeARecords(zone, name);
    if(count == 0)
    {
      return "No matching A records found for name '" + name + "' in zone " + zone;
    }
    return "Removed " + count + " A record(s).";
  }

  @Command(name = "add-txt",
           group = "Micetro Commands",
           description = "Add a TXT DNS record to all refs of a zone")
  public String addTxt(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name (e.g. _acme-challenge.www.example.de.)",
            required = true) String name,
    @Option(longName = "data",
            description = "TXT record value",
            required = true) String data)
  {
    zone = normalizeZone(zone);
    log.debug("add-txt: zone={}, name={}", zone, name);
    return formatAddResult(
      "TXT", zone, micetroService.addTxtRecords(zone, name, data));
  }

  /**
   * Zone names in Micetro are fully qualified and end with a trailing dot.
   * Append it if the caller left it off (e.g. "example.de" -> "example.de.").
   */
  private static String normalizeZone(String zone)
  {
    if(zone != null && !zone.isBlank() && !zone.endsWith("."))
    {
      return zone + ".";
    }
    return zone;
  }

  private String formatAddResult(String type, String zone, AddResult result)
  {
    if(result.succeeded() == 0 && result.errors().isEmpty())
    {
      return "Zone not found: " + zone;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Added ").append(type).append(" record to ")
      .append(result.succeeded()).append(" zone ref(s).");
    if(!result.errors().isEmpty())
    {
      sb.append(System.lineSeparator())
        .append(result.errors().size()).append(" ref(s) failed:");
      for(String error : result.errors())
      {
        sb.append(System.lineSeparator()).append("  ").append(error);
      }
    }
    return sb.toString();
  }

  @Command(name = "remove-txt",
           group = "Micetro Commands",
           description = "Remove TXT DNS records (tagged l9g-cheese) from all refs of a zone")
  public String removeTxt(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name (e.g. _acme-challenge.www.example.de.)",
            required = true) String name)
  {
    zone = normalizeZone(zone);
    log.debug("remove-txt: zone={}, name={}", zone, name);
    int count = micetroService.removeTxtRecords(zone, name);
    if(count == 0)
    {
      return "No matching records found for name '" + name + "' in zone " + zone;
    }
    return "Removed " + count + " TXT record(s).";
  }

}

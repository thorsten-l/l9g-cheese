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
import l9g.app.cheese.micetro.MicetroApiException;
import l9g.app.cheese.micetro.MicetroService;
import l9g.app.cheese.micetro.MicetroService.AddResult;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Spring Shell commands in the {@code "Micetro Commands"} group for inspecting
 * and managing DNS records and IP ranges in the Micetro (Men&amp;Mice) IPAM
 * server.
 *
 * <p>The class exposes listing commands ({@code list-zones},
 * {@code list-records}, {@code list-ranges}, {@code list-ipaddrs}),
 * search ({@code search}), record-creation commands ({@code add-a},
 * {@code add-txt}, {@code add-record}), record-removal commands
 * ({@code remove-a}, {@code remove-txt}) and reference-based object operations
 * ({@code remove-objects}, {@code enable-objects}, {@code disable-objects}).
 * All business logic is delegated to {@link MicetroService}; this class only
 * parses options, normalizes input and renders the user-facing output (notably
 * the zebra-striped record tables built with JLine's
 * {@link AttributedStringBuilder}). Records this app creates carry the
 * {@code l9g-cheese} comment tag, and the targeted removal commands only touch
 * records with that tag — {@code remove-objects} is the exception and bypasses
 * that guard.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Component
public class ZoneCommands
{
  private final MicetroService micetroService;

  /**
   * Creates the command holder with its collaborating service.
   *
   * @param micetroService the service that performs all Micetro JSON-RPC calls
   *                       and holds the DNS/IPAM business logic
   */
  public ZoneCommands(MicetroService micetroService)
  {
    this.micetroService = micetroService;
  }

  /**
   * {@code list-records} command: lists every real DNS record of a zone across
   * all of its refs (views), sorted alphabetically by name. With
   * {@code --separate} each matching view is rendered as its own
   * {@code === displayName ===} block; with {@code --show-refs} the table also
   * shows the per-record object reference and enabled state.
   *
   * @param zone     the DNS zone name ({@code --zone}, required); a missing
   *                 trailing dot is appended by {@link #normalizeZone}
   * @param separate when {@code true} ({@code --separate}, optional, default
   *                 {@code false}) group the output by zone display name into
   *                 separate blocks
   * @param showRefs when {@code true} ({@code --show-refs}, optional, default
   *                 {@code false}) add the {@code REF} and {@code ENABLED}
   *                 columns
   * @return the rendered record table(s), or a "no records found" message if the
   *         zone has none
   */
  @Command(name = "list-records",
           group = "Micetro Commands",
           description = "List all DNS records in a zone, sorted alphabetically by name")
  public String listRecords(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "separate",
            description = "Output each matching zone (view) in its own block",
            defaultValue = "false") boolean separate,
    @Option(longName = "show-refs",
            description = "Also show each record's object reference and enabled state",
            defaultValue = "false") boolean showRefs)
  {
    zone = normalizeZone(zone);
    log.debug("list-records: zone={}, separate={}, showRefs={}",
      zone, separate, showRefs);
    List<Map<String, Object>> records = micetroService.listZoneRecords(zone);

    if(records.isEmpty())
    {
      return "No records found in zone " + zone;
    }

    if( ! separate)
    {
      return renderRecordTable(records, true, showRefs);
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
      sb.append(renderRecordTable(group.getValue(), false, showRefs));
    }
    return sb.toString();
  }

  /**
   * {@code search} command: finds DNS records whose name matches across all refs
   * of a zone and renders them as a table. With {@code --show-refs} the table
   * gains {@code REF} and {@code ENABLED} columns (the SOA ref is deliberately
   * blanked so it cannot be passed to {@code remove-objects} by accident).
   *
   * @param zone     the DNS zone name ({@code --zone}, required); normalized via
   *                 {@link #normalizeZone}
   * @param name     the record name to match ({@code --name}, required)
   * @param showRefs when {@code true} ({@code --show-refs}, optional, default
   *                 {@code false}) add the {@code REF} and {@code ENABLED}
   *                 columns
   * @return the rendered record table, or a "no records found" message when
   *         nothing matches
   */
  @Command(name = "search",
           group = "Micetro Commands",
           description = "Search DNS records by name across all refs of a zone (lists type and data)")
  public String search(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name to match",
            required = true) String name,
    @Option(longName = "show-refs",
            description = "Also show each record's object reference (for remove-object)",
            defaultValue = "false") boolean showRefs)
  {
    zone = normalizeZone(zone);
    log.debug("search: zone={}, name={}, showRefs={}", zone, name, showRefs);
    List<Map<String, Object>> records = micetroService.searchRecords(zone, name);

    if(records.isEmpty())
    {
      return "No records found for name '" + name + "' in zone " + zone;
    }

    return renderRecordTable(records, true, showRefs);
  }

  /////////////////////////////////////////////////////////////////////////////
  // table rendering for DNS record listings (search / range / list-records)

  private static final int MAX_CELL_WIDTH = 60;

  private static final String ENABLED_CHECK = "✅";

  private static final String ENABLED_CROSS = "❌";

  /**
   * Sanitizes a cell value for tabular output: replaces tab/newline/carriage
   * return with spaces and truncates to {@link #MAX_CELL_WIDTH} characters
   * (appending {@code …}) so long TXT rdata does not blow out the columns.
   *
   * @param value the raw cell value (may be {@code null})
   * @return the sanitized, length-limited string ({@code ""} for {@code null})
   */
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

  /**
   * Convenience overload of
   * {@link #renderRecordTable(List, boolean, boolean)} without the
   * {@code REF}/{@code ENABLED} columns.
   *
   * @param records  the DNS records to render (raw maps from the service)
   * @param withZone when {@code true} include the {@code ZONE} column (the
   *                 record's stashed {@code displayName})
   * @return the rendered table as an ANSI-styled string
   */
  private static String renderRecordTable(
    List<Map<String, Object>> records, boolean withZone)
  {
    return renderRecordTable(records, withZone, false);
  }

  /**
   * Builds the columns and rows for a DNS record table
   * ({@code NAME TYPE DATA TTL COMMENT}, optionally {@code ZONE}, optionally
   * {@code REF}/{@code ENABLED}) and delegates the styling to
   * {@link #renderStripedTable}. The TYPE column is tinted per type; when refs
   * are shown the SOA ref is blanked and the ENABLED column shows a coloured
   * check/cross glyph.
   *
   * @param records  the DNS records to render (raw maps from the service, each
   *                 carrying {@code name}/{@code type}/{@code data}/{@code ttl}/
   *                 {@code comment} and optionally {@code displayName}/
   *                 {@code ref}/{@code enabled})
   * @param withZone when {@code true} append the {@code ZONE} column
   * @param withRef  when {@code true} append the {@code REF} and {@code ENABLED}
   *                 columns
   * @return the rendered table as an ANSI-styled string
   */
  private static String renderRecordTable(
    List<Map<String, Object>> records, boolean withZone, boolean withRef)
  {
    List<String> headerList = new ArrayList<>(
      List.of("NAME", "TYPE", "DATA", "TTL", "COMMENT"));
    if(withZone)
    {
      headerList.add("ZONE");
    }
    int statusColumn = -1;
    if(withRef)
    {
      headerList.add("REF");
      headerList.add("ENABLED");
      statusColumn = headerList.size() - 1;
    }
    String[] headers = headerList.toArray(new String[0]);

    List<String[]> rows = new ArrayList<>();
    for(Map<String, Object> r : records)
    {
      List<String> row = new ArrayList<>(List.of(
        cell(r.get("name")), cell(r.get("type")), cell(r.get("data")),
        cell(r.get("ttl")), cell(r.get("comment"))));
      if(withZone)
      {
        row.add(cell(r.get("displayName")));
      }
      if(withRef)
      {
        // blank the SOA ref so it can't be fed to remove-objects by accident
        boolean soa = "SOA".equalsIgnoreCase(String.valueOf(r.get("type")));
        row.add(soa ? "" : cell(r.get("ref")));
        row.add(isEnabled(r) ? ENABLED_CHECK : ENABLED_CROSS);
      }
      rows.add(row.toArray(new String[0]));
    }

    // TYPE column (index 1) -> per-type background; statusColumn -> green/red glyph
    return renderStripedTable(headers, rows, 1, statusColumn);
  }

  /**
   * Determines whether a record is enabled. A {@code Boolean} {@code enabled}
   * value is used directly; an absent value is treated as enabled (records are
   * enabled unless explicitly disabled), and any other value is parsed as a
   * boolean string.
   *
   * @param record the raw record map
   * @return {@code true} if the record is (or is assumed to be) enabled
   */
  private static boolean isEnabled(Map<String, Object> record)
  {
    Object enabled = record.get("enabled");
    if(enabled instanceof Boolean b)
    {
      return b;
    }
    // absent -> treat as enabled (records are enabled unless explicitly disabled)
    return enabled == null || Boolean.parseBoolean(String.valueOf(enabled));
  }

  /**
   * Convenience overload of
   * {@link #renderStripedTable(String[], List, int, int)} without a status
   * column.
   *
   * @param headers    the column header labels
   * @param rows       the table rows (each a same-length array of cell strings)
   * @param typeColumn index of the column to tint per record type, or
   *                   {@code -1} for none
   * @return the rendered table as an ANSI-styled string
   */
  private static String renderStripedTable(
    String[] headers, List<String[]> rows, int typeColumn)
  {
    return renderStripedTable(headers, rows, typeColumn, -1);
  }

  /**
   * Render a zebra-striped table: bold headers, alternating light-grey row
   * backgrounds with black text. When {@code typeColumn >= 0}, that column is
   * tinted per {@link #typeBackground} (used by the DNS record tables); pass
   * {@code -1} for a plain striped table (list-zones / list-ranges). When
   * {@code statusColumn >= 0}, that column's {@code ✓}/{@code ✗} glyph is
   * coloured green/red (used by the ENABLED column of search --show-refs).
   *
   * @param headers      the column header labels (rendered bold)
   * @param rows         the table rows; each is an array of cell strings aligned
   *                     to {@code headers}
   * @param typeColumn   index of the column whose background is tinted per
   *                     record type, or {@code -1} for no per-type tint
   * @param statusColumn index of the column whose enabled-glyph is coloured
   *                     green/red, or {@code -1} for none
   * @return the rendered table as an ANSI-styled string (trailing whitespace
   *         stripped)
   */
  private static String renderStripedTable(
    String[] headers, List<String[]> rows, int typeColumn, int statusColumn)
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
        else if(i == statusColumn)
        {
          int fg = ENABLED_CROSS.equals(row[i])
            ? AttributedStyle.RED : AttributedStyle.GREEN;
          cellStyle = AttributedStyle.DEFAULT.foreground(fg).background(zebra);
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

  /**
   * Right-pads a string with spaces to the given column width.
   *
   * @param s     the value to pad (must be no longer than {@code width})
   * @param width the target field width
   * @return {@code s} followed by enough spaces to reach {@code width}
   */
  private static String pad(String s, int width)
  {
    return s + " ".repeat(width - s.length());
  }

  // two light grey tones for the zebra striping (256-colour palette)
  private static final int ROW_BG_A = 255;

  private static final int ROW_BG_B = 252;

  /**
   * Maps a DNS record type to its 256-colour background code for the TYPE
   * column (e.g. A {@code 153}, AAAA {@code 117}, TXT {@code 151}).
   *
   * @param type the record type label (e.g. {@code "A"}, {@code "TXT"})
   * @return the 256-colour palette code, or {@code -1} for an unknown type
   *         (which then keeps the zebra-stripe background)
   */
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
      case "SVCB" ->
        158;   // mint
      case "HTTPS" ->
        195;   // pale cyan
      case "TLSA" ->
        219;   // light magenta
      case "SSHFP" ->
        186;   // light yellow-green
      case "RESV" ->
        216;   // light orange (DHCP reservation)
      case "LEASE" ->
        230;   // pale yellow (DHCP lease)
      default ->
        -1;
    };
  }

  /**
   * {@code list-ipaddrs} command: lists every A record whose IP starts with the
   * given net prefix, sorted numerically by IP. The prefix may be given
   * positionally or via {@code --net}; the named option wins if both are
   * present.
   *
   * @param netOption the IPv4 prefix to match ({@code --net}, optional;
   *                  e.g. {@code "141.41.2."})
   * @param netArg    the positional net prefix (index 0, optional alternative to
   *                  {@code --net}, default {@code ""})
   * @return the rendered record table, a usage message if no prefix was given,
   *         or a "no A records found" message when nothing matches
   */
  @Command(name = "list-ipaddrs",
           group = "Micetro Commands",
           description = "List A records whose IP starts with the given net prefix, sorted by IP")
  public String listIpaddrs(
    @Option(longName = "net",
            description = "IPv4 prefix the address must start with (e.g. 141.41.2.)",
            required = false) String netOption,
    @Argument(index = 0,
              description = "Net prefix (positional alternative to --net)",
              defaultValue = "") String netArg)
  {
    // accept either "list-ipaddrs 141.41.2." (positional) or
    // "list-ipaddrs --net 141.41.2."; the named option wins if both are given.
    String net = (netOption != null && !netOption.isBlank())
      ? netOption : netArg;
    log.debug("list-ipaddrs: net={}", net);

    if(net == null || net.isBlank())
    {
      return "Missing net prefix. Usage: list-ipaddrs <net>  "
        + "(or: list-ipaddrs --net <net>)";
    }

    List<Map<String, Object>> records = micetroService.rangeRecords(net);

    if(records.isEmpty())
    {
      return "No A records found starting with '" + net + "'";
    }

    return renderRecordTable(records, true);
  }

  /**
   * {@code list-ranges} command: lists the IP ranges visible to the configured
   * API user as a {@code NAME FROM TO} table.
   *
   * @param filter an optional Micetro range filter ({@code --filter}, default
   *               {@code "type!=Container"}; e.g. {@code "name=^10.230"})
   * @return the rendered ranges table, or a "no ranges visible" message
   *         (mentioning the filter when one is set)
   */
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

  /**
   * {@code list-zones} command: lists the display names of all primary DNS zones
   * as a single-column {@code ZONE} table.
   *
   * @return the rendered zone table, or {@code "No zones found."} when there are
   *         none
   */
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

  /**
   * {@code add-a} command: adds an A record to every ref of a zone, tagged with
   * the {@code l9g-cheese} comment.
   *
   * @param zone the DNS zone name ({@code --zone}, required); normalized via
   *             {@link #normalizeZone}
   * @param name the record name ({@code --name}, required; e.g.
   *             {@code host.example.de.})
   * @param ip   the IPv4 address ({@code --ip}, required)
   * @param ttl  the TTL in seconds ({@code --ttl}, required)
   * @return a summary of how many zone refs the record was added to (and any
   *         per-ref errors), via {@link #formatAddResult}
   */
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

  // record types accepted by add-record (rendered in the order shown to users).
  // SOA is intentionally excluded: each zone has exactly one, auto-managed.
  private static final List<String> ADD_RECORD_TYPES = List.of(
    "A", "AAAA", "MX", "CNAME", "TXT", "NS", "SRV", "PTR", "CAA",
    "SVCB", "HTTPS", "TLSA", "SSHFP");

  /**
   * {@code add-record} command: adds a DNS record of a given type to every ref
   * of a zone. The type is upper-cased and validated against
   * {@link #ADD_RECORD_TYPES} (SOA is excluded as it is auto-managed); an
   * unknown type yields an error message listing the allowed types.
   *
   * @param zone the DNS zone name ({@code --zone}, required); normalized via
   *             {@link #normalizeZone}
   * @param name the record name ({@code --name}, required)
   * @param type the record type ({@code --type}, required); validated against
   *             the allowed list
   * @param data the record data / rdata ({@code --data}, required)
   * @param ttl  the TTL in seconds ({@code --ttl}, optional; defaults to the
   *             zone default)
   * @return an "invalid type" message when the type is not allowed, otherwise a
   *         summary of the add via {@link #formatAddResult}
   */
  @Command(name = "add-record",
           group = "Micetro Commands",
           description = "Add a DNS record of a given type to all refs of a zone")
  public String addRecord(
    @Option(longName = "zone",
            description = "DNS zone name (e.g. example.de.)",
            required = true) String zone,
    @Option(longName = "name",
            description = "Record name (e.g. host.example.de.)",
            required = true) String name,
    @Option(longName = "type",
            description = "Record type (A, AAAA, MX, CNAME, TXT, NS, SRV, PTR, CAA, SVCB, HTTPS, TLSA, SSHFP)",
            required = true) String type,
    @Option(longName = "data",
            description = "Record data / rdata (e.g. an IP, target host or text)",
            required = true) String data,
    @Option(longName = "ttl",
            description = "TTL in seconds (optional; defaults to the zone default)",
            required = false) String ttl)
  {
    zone = normalizeZone(zone);
    String recordType = type == null ? "" : type.trim().toUpperCase();
    if( ! ADD_RECORD_TYPES.contains(recordType))
    {
      return "Invalid type '" + type + "'. Allowed: "
        + String.join(", ", ADD_RECORD_TYPES);
    }
    log.debug("add-record: zone={}, name={}, type={}", zone, name, recordType);
    return formatAddResult(recordType, zone,
      micetroService.addRecords(zone, recordType, name, data, ttl));
  }

  /**
   * {@code remove-a} command: removes the A records tagged {@code l9g-cheese}
   * for a name from every ref of a zone. With {@code --dry} it only lists the
   * records that would be deleted without making changes.
   *
   * @param zone the DNS zone name ({@code --zone}, required); normalized via
   *             {@link #normalizeZone}
   * @param name the record name ({@code --name}, required)
   * @param dry  when {@code true} ({@code --dry}, optional, default
   *             {@code false}) perform a dry run only
   * @return a dry-run listing, a "removed N record(s)" message, or a "no
   *         matching records" message
   */
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

  /**
   * {@code add-txt} command: adds a TXT record to every ref of a zone, tagged
   * with the {@code l9g-cheese} comment (used for ACME DNS-01 challenges).
   *
   * @param zone the DNS zone name ({@code --zone}, required); normalized via
   *             {@link #normalizeZone}
   * @param name the record name ({@code --name}, required; e.g.
   *             {@code _acme-challenge.www.example.de.})
   * @param data the TXT record value ({@code --data}, required)
   * @return a summary of how many zone refs the record was added to via
   *         {@link #formatAddResult}
   */
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

  /**
   * Formats the outcome of an add operation into a user-facing message: a "zone
   * not found" message when nothing was added and no errors occurred, otherwise
   * the number of zone refs the record was added to, the created object
   * reference(s), and any per-ref errors.
   *
   * @param type   the record type label used in the message (e.g. {@code "A"})
   * @param zone   the normalized zone name (used in the not-found message)
   * @param result the {@link AddResult} returned by the service
   * @return the formatted result message
   */
  private String formatAddResult(String type, String zone, AddResult result)
  {
    if(result.succeeded() == 0 && result.errors().isEmpty())
    {
      return "Zone not found: " + zone;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Added ").append(type).append(" record to ")
      .append(result.succeeded()).append(" zone ref(s).");
    if(result.refs() != null && !result.refs().isEmpty())
    {
      sb.append(System.lineSeparator())
        .append("Created object ref(s): ")
        .append(String.join(", ", result.refs()));
    }
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

  /**
   * {@code remove-txt} command: removes the TXT records tagged
   * {@code l9g-cheese} for a name from every ref of a zone.
   *
   * @param zone the DNS zone name ({@code --zone}, required); normalized via
   *             {@link #normalizeZone}
   * @param name the record name ({@code --name}, required)
   * @return a "removed N TXT record(s)" message, or a "no matching records"
   *         message
   */
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

  /**
   * {@code remove-objects} command: removes Micetro objects by their references
   * (e.g. as printed by {@code search --show-refs}).
   *
   * <p><strong>Destructive:</strong> unlike the other removal commands this
   * <em>bypasses</em> the {@code l9g-cheese} comment tag and zone permission
   * guards, so it can delete any object. When not a dry run it therefore prompts
   * for interactive {@code [y/N]} confirmation; anything other than {@code y}/
   * {@code yes} (including a read failure / EOF) aborts without changes.
   *
   * @param ctx     the auto-injected command context, used to read the
   *                confirmation input
   * @param objRefs comma-separated object references ({@code --obj-refs},
   *                required; e.g. {@code {#13-#1},{#13-#2}})
   * @param dry     when {@code true} ({@code --dry}, optional, default
   *                {@code false}) only report what would be removed
   * @return a usage/dry-run/abort message, a "removed N object(s)" success
   *         message, or an error message if Micetro reported failures
   */
  @Command(name = "remove-objects",
           group = "Micetro Commands",
           description = "Remove Micetro objects by their references (e.g. from search --show-refs)")
  public String removeObjects(
    CommandContext ctx,
    @Option(longName = "obj-refs",
            description = "Comma-separated object references (e.g. {#13-#1},{#13-#2})",
            required = true) String objRefs,
    @Option(longName = "dry",
            description = "Show what would be removed without actually deleting it",
            defaultValue = "false") boolean dry)
  {
    List<String> refs = parseObjRefs(objRefs);
    if(refs.isEmpty())
    {
      return "Missing object reference(s). "
        + "Usage: remove-objects --obj-refs <ref>,<ref>,...";
    }
    log.debug("remove-objects: refs={}, dry={}", refs, dry);

    String joined = String.join(", ", refs);
    if(dry)
    {
      return "[DRY-RUN] Would remove " + refs.size() + " object(s): " + joined
        + " (no changes made).";
    }

    // remove-objects bypasses the l9g-cheese guard, so confirm interactively
    // before deleting. Anything other than y/yes (incl. EOF) aborts.
    String answer;
    try
    {
      answer = ctx.inputReader().readInput(
        "Remove " + refs.size() + " object(s) [" + joined + "]? This is NOT "
        + "limited to l9g-cheese records and cannot be undone. [y/N]: ");
    }
    catch(Exception e)
    {
      log.debug("confirmation read failed", e);
      return "Aborted: could not read confirmation.";
    }
    String confirm = answer == null ? "" : answer.trim();
    if( ! confirm.equalsIgnoreCase("y") && ! confirm.equalsIgnoreCase("yes"))
    {
      return "Aborted. No objects were removed.";
    }

    try
    {
      List<Object> errors = micetroService.removeObjectsByRefs(refs);
      if(errors.isEmpty())
      {
        return "Removed " + refs.size() + " object(s): " + joined;
      }
      return "Micetro reported " + errors.size() + " error(s) removing objects: "
        + errors;
    }
    catch(MicetroApiException e)
    {
      return "Failed to remove objects: " + e.getMessage();
    }
  }

  /**
   * {@code enable-objects} command: enables Micetro objects by their references
   * (e.g. as printed by {@code search --show-refs}).
   *
   * @param objRefs comma-separated object references ({@code --obj-refs},
   *                required; e.g. {@code {#13-#1},{#13-#2}})
   * @return the result summary from {@link #setObjectsEnabled}
   */
  @Command(name = "enable-objects",
           group = "Micetro Commands",
           description = "Enable Micetro objects by their references (e.g. from search --show-refs)")
  public String enableObjects(
    @Option(longName = "obj-refs",
            description = "Comma-separated object references (e.g. {#13-#1},{#13-#2})",
            required = true) String objRefs)
  {
    return setObjectsEnabled(objRefs, true);
  }

  /**
   * {@code disable-objects} command: disables Micetro objects by their
   * references (e.g. as printed by {@code search --show-refs}).
   *
   * @param objRefs comma-separated object references ({@code --obj-refs},
   *                required; e.g. {@code {#13-#1},{#13-#2}})
   * @return the result summary from {@link #setObjectsEnabled}
   */
  @Command(name = "disable-objects",
           group = "Micetro Commands",
           description = "Disable Micetro objects by their references (e.g. from search --show-refs)")
  public String disableObjects(
    @Option(longName = "obj-refs",
            description = "Comma-separated object references (e.g. {#13-#1},{#13-#2})",
            required = true) String objRefs)
  {
    return setObjectsEnabled(objRefs, false);
  }

  /**
   * Shared implementation for {@code enable-objects} / {@code disable-objects}:
   * parses the refs, sets their enabled state via the service and formats a
   * summary of how many succeeded plus any per-ref failures.
   *
   * @param objRefs comma-separated object references
   * @param enabled {@code true} to enable, {@code false} to disable
   * @return a usage message when no refs were given, otherwise an
   *         "Enabled/Disabled N object(s)" summary including any failures
   */
  private String setObjectsEnabled(String objRefs, boolean enabled)
  {
    String verb = enabled ? "enable" : "disable";
    List<String> refs = parseObjRefs(objRefs);
    if(refs.isEmpty())
    {
      return "Missing object reference(s). "
        + "Usage: " + verb + "-objects --obj-refs <ref>,<ref>,...";
    }
    log.debug("{}-objects: refs={}", verb, refs);

    List<String> errors = micetroService.setObjectsEnabled(refs, enabled);
    StringBuilder sb = new StringBuilder();
    sb.append(enabled ? "Enabled " : "Disabled ")
      .append(refs.size() - errors.size()).append(" object(s).");
    if( ! errors.isEmpty())
    {
      sb.append(System.lineSeparator())
        .append(errors.size()).append(" ref(s) failed:");
      for(String error : errors)
      {
        sb.append(System.lineSeparator()).append("  ").append(error);
      }
    }
    return sb.toString();
  }

  /**
   * Splits a comma-separated {@code --obj-refs} value into trimmed, non-empty
   * object references.
   *
   * @param objRefs the raw comma-separated value (may be {@code null})
   * @return the parsed references (empty if {@code objRefs} is {@code null} or
   *         contains no non-blank entries)
   */
  // split a comma-separated --obj-refs value into trimmed, non-empty refs
  private static List<String> parseObjRefs(String objRefs)
  {
    List<String> refs = new ArrayList<>();
    if(objRefs != null)
    {
      for(String r : objRefs.split(","))
      {
        String trimmed = r.trim();
        if( ! trimmed.isEmpty())
        {
          refs.add(trimmed);
        }
      }
    }
    return refs;
  }

}

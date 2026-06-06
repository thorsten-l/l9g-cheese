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

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Spring Shell commands in the {@code "DNS Commands"} group for real DNS
 * resolution.
 *
 * <p>Unlike the {@code "Micetro Commands"}, this group queries the actual DNS
 * system (via JNDI's {@code com.sun.jndi.dns.DnsContextFactory}), not the
 * Micetro IPAM server — useful for verifying what is actually published.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Component
public class DnsCommands
{
  private static final String[] RECORD_TYPES =
  {
    "A", "AAAA", "CNAME", "MX", "TXT", "NS"
  };

  /**
   * {@code lookup} command: resolves a fully qualified domain name via JNDI DNS
   * and prints the {@link #RECORD_TYPES} records found, one
   * {@code <type>  <value>} per line. The FQDN may be given positionally or via
   * {@code --fqdn}; the named option wins if both are present.
   *
   * @param fqdnOption the FQDN to resolve ({@code --fqdn}, optional; e.g.
   *                   {@code www.example.de})
   * @param fqdnArg    the positional FQDN (index 0, optional alternative to
   *                   {@code --fqdn}, default {@code ""})
   * @return the resolved records, a usage message if no FQDN was given, a
   *         "not found"/"no records" message, or an error message if the lookup
   *         failed
   */
  @Command(name = "lookup",
           group = "DNS Commands",
           description = "Resolve a FQDN via DNS and print the records found")
  public String lookup(
    @Option(longName = "fqdn",
            description = "Fully qualified domain name to resolve (e.g. www.example.de)",
            required = false) String fqdnOption,
    @Argument(index = 0,
              description = "FQDN to resolve (positional alternative to --fqdn)",
              defaultValue = "") String fqdnArg)
  {
    // accept either "lookup www.example.de" (positional) or
    // "lookup --fqdn www.example.de"; the named option wins if both are given.
    String fqdn = (fqdnOption != null && !fqdnOption.isBlank())
      ? fqdnOption : fqdnArg;
    log.debug("lookup: fqdn={}", fqdn);

    if(fqdn == null || fqdn.isBlank())
    {
      return "Missing FQDN. Usage: lookup <fqdn>  (or: lookup --fqdn <fqdn>)";
    }

    Hashtable<String, Object> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY,
      "com.sun.jndi.dns.DnsContextFactory");

    DirContext ctx = null;
    try
    {
      ctx = new InitialDirContext(env);
      Attributes attrs = ctx.getAttributes(fqdn, RECORD_TYPES);

      if(attrs == null || attrs.size() == 0)
      {
        return "No DNS records found for " + fqdn;
      }

      StringBuilder sb = new StringBuilder();
      NamingEnumeration<? extends Attribute> all = attrs.getAll();
      while(all.hasMore())
      {
        Attribute attr = all.next();
        NamingEnumeration<?> values = attr.getAll();
        while(values.hasMore())
        {
          if(sb.length() > 0)
          {
            sb.append(System.lineSeparator());
          }
          sb.append(attr.getID()).append("  ").append(values.next());
        }
      }
      return sb.toString();
    }
    catch(NameNotFoundException e)
    {
      return "Not found: " + fqdn;
    }
    catch(NamingException e)
    {
      log.debug("lookup failed", e);
      return "DNS lookup failed for " + fqdn + ": " + e.getMessage();
    }
    finally
    {
      if(ctx != null)
      {
        try
        {
          ctx.close();
        }
        catch(NamingException e)
        {
          log.debug("ctx close failed", e);
        }
      }
    }
  }

}

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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandOption;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Custom {@code help} command that replaces Spring Shell's built-in one (which
 * is switched off via {@code spring.shell.command.help.enabled=false} in
 * application.yaml).
 *
 * <p>The built-in {@code Help} renderer hard-codes a {@code --help} / {@code -h}
 * line into every command's help page, but that option does nothing at runtime
 * (Spring Shell 4.0.2 parses it and short-circuits execution without rendering
 * anything). To avoid confusing users with a dead option, this command renders
 * the same NAME / SYNOPSIS / OPTIONS layout but omits the {@code --help} lines.
 *
 * <p>The {@link CommandRegistry} is obtained from the {@link CommandContext} at
 * execution time rather than injected. Injecting it would create a bean cycle:
 * the registry has to know every {@code @Command} bean (including this one), so
 * this component cannot depend on the registry in turn. The adapter passes the
 * {@code CommandContext} into the command method automatically.
 *
 * <p>Note: {@code org.springframework.shell.core.command.Command} (the runtime
 * command interface) and {@code ...command.annotation.Command} (this method's
 * annotation) share a simple name; the interface is referenced fully qualified
 * below so {@code @Command} can stay imported like in the other command classes.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Component
public class HelpCommands
{
  private final ApplicationContext applicationContext;

  // command name -> positional argument label (the related option's longName),
  // for commands whose @Command method has an @Argument parameter. Built lazily.
  private Map<String, String> positionalLabels;

  public HelpCommands(ApplicationContext applicationContext)
  {
    this.applicationContext = applicationContext;
  }

  @Command(name = "help",
           group = "Built-In Commands",
           description = "Show help about available commands")
  public String help(
    CommandContext ctx,
    @Argument(index = 0,
              description = "Command to show detailed help for",
              defaultValue = "") String command)
  {
    log.debug("help: command={}", command);
    CommandRegistry commandRegistry = ctx.commandRegistry();
    if(command == null || command.isBlank())
    {
      return overview(commandRegistry);
    }
    return detail(commandRegistry, command.trim());
  }

  /////////////////////////////////////////////////////////////////////////////
  // overview: all visible commands grouped by their @Command group

  private String overview(CommandRegistry commandRegistry)
  {
    // group name -> (command name -> description), both kept sorted
    Map<String, Map<String, String>> groups = new TreeMap<>();
    for(org.springframework.shell.core.command.Command c
      : commandRegistry.getCommands())
    {
      if(c.isHidden())
      {
        continue;
      }
      String group = c.getGroup() != null && !c.getGroup().isBlank()
        ? c.getGroup() : "Default";
      String names = c.getName();
      if(c.getAliases() != null && !c.getAliases().isEmpty())
      {
        names += ", " + String.join(", ", c.getAliases());
      }
      groups.computeIfAbsent(group, k -> new TreeMap<>())
        .put(names, c.getDescription() != null ? c.getDescription() : "");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("AVAILABLE COMMANDS").append(System.lineSeparator());
    for(Map.Entry<String, Map<String, String>> group : groups.entrySet())
    {
      sb.append(System.lineSeparator()).append(group.getKey())
        .append(System.lineSeparator());
      for(Map.Entry<String, String> cmd : group.getValue().entrySet())
      {
        sb.append('\t').append(cmd.getKey()).append(": ")
          .append(cmd.getValue()).append(System.lineSeparator());
      }
    }
    return sb.toString().stripTrailing();
  }

  /////////////////////////////////////////////////////////////////////////////
  // detail: NAME / SYNOPSIS / OPTIONS for one command (no --help/-h lines)

  private String detail(CommandRegistry commandRegistry, String name)
  {
    org.springframework.shell.core.command.Command c =
      commandRegistry.getCommandByName(name);
    if(c == null)
    {
      c = commandRegistry.getCommandByAlias(name);
    }
    if(c == null)
    {
      return "Unknown command '" + name + "'. Type 'help' for a list.";
    }

    // the built-in adds a synthetic help option; ignore any option named "help"
    List<CommandOption> options = c.getOptions() == null
      ? List.of()
      : c.getOptions().stream()
        .filter(o -> !"help".equals(o.longName()) && o.shortName() != 'h')
        .toList();

    StringBuilder sb = new StringBuilder();

    sb.append("NAME").append(System.lineSeparator());
    sb.append('\t').append(c.getName());
    if(c.getDescription() != null && !c.getDescription().isBlank())
    {
      sb.append(" - ").append(c.getDescription());
    }
    sb.append(System.lineSeparator()).append(System.lineSeparator());

    sb.append("SYNOPSIS").append(System.lineSeparator());
    sb.append('\t').append(c.getName());
    // commands that also accept their value positionally (see DnsCommands /
    // ZoneCommands) show it first, e.g. "lookup [<fqdn>] --fqdn String"
    String positional = positionalLabels().get(c.getName());
    if(positional != null)
    {
      sb.append(" [<").append(positional).append(">]");
    }
    for(CommandOption o : options)
    {
      sb.append(' ').append(synopsisOption(o));
    }
    sb.append(System.lineSeparator());

    if( ! options.isEmpty())
    {
      sb.append(System.lineSeparator()).append("OPTIONS")
        .append(System.lineSeparator());
      for(CommandOption o : options)
      {
        sb.append('\t').append("--").append(o.longName());
        if(o.type() != null)
        {
          sb.append(' ').append(o.type().getSimpleName());
        }
        sb.append(System.lineSeparator());
        if(o.description() != null && !o.description().isBlank())
        {
          sb.append('\t').append(o.description()).append(System.lineSeparator());
        }
        sb.append('\t').append(constraint(o)).append(System.lineSeparator());
        sb.append(System.lineSeparator());
      }
    }
    return sb.toString().stripTrailing();
  }

  /**
   * Scan our {@code @Command} beans for methods that declare an {@code @Argument}
   * parameter (i.e. accept their value positionally) and map the command name to
   * a label taken from the method's first {@code @Option} (e.g. {@code fqdn}).
   * The registered {@code Command} objects do not expose {@code @Argument}
   * metadata, so this reads it from the source methods reflectively. Computed
   * once and cached; failures degrade to "no positional hint".
   */
  private synchronized Map<String, String> positionalLabels()
  {
    if(positionalLabels != null)
    {
      return positionalLabels;
    }
    Map<String, String> labels = new HashMap<>();
    try
    {
      for(Object bean
        : applicationContext.getBeansWithAnnotation(Component.class).values())
      {
        for(Method method : AopUtils.getTargetClass(bean).getDeclaredMethods())
        {
          Command command = method.getAnnotation(Command.class);
          if(command == null)
          {
            continue;
          }
          boolean hasArgument = false;
          String optionLabel = null;
          for(Parameter parameter : method.getParameters())
          {
            if(parameter.isAnnotationPresent(Argument.class))
            {
              hasArgument = true;
            }
            Option option = parameter.getAnnotation(Option.class);
            if(option != null && optionLabel == null)
            {
              optionLabel = option.longName();
            }
          }
          if(hasArgument && command.name().length > 0)
          {
            labels.put(command.name()[0],
              optionLabel != null ? optionLabel : "arg");
          }
        }
      }
    }
    catch(RuntimeException e)
    {
      log.debug("positional-argument scan failed", e);
    }
    positionalLabels = labels;
    return positionalLabels;
  }

  // Standard CLI convention: optional options are wrapped in [ ], mandatory
  // ones are shown bare.
  private static String synopsisOption(CommandOption o)
  {
    String body = "--" + o.longName()
      + (o.type() != null ? " " + o.type().getSimpleName() : "");
    return isRequired(o) ? body : "[" + body + "]";
  }

  private static String constraint(CommandOption o)
  {
    if(isRequired(o))
    {
      return "[Mandatory]";
    }
    String def = o.defaultValue();
    if(def != null && !def.isBlank())
    {
      return "[Optional, default = " + def + "]";
    }
    return "[Optional]";
  }

  private static boolean isRequired(CommandOption o)
  {
    return Boolean.TRUE.equals(o.required());
  }
}

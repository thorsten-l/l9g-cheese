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

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.core.NonInteractiveShellRunner;
import org.springframework.shell.core.ShellRunner;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.jline.PromptProvider;

/**
 * Spring configuration for the interactive JLine shell.
 *
 * <p>It customizes the interactive prompt by providing a
 * {@link PromptProvider} bean that replaces Spring Shell's default
 * {@code "shell:>"} with the styled {@code "cheese:> "} prompt, and restores
 * the Spring Shell 3.x hybrid launch behavior via
 * {@link #springShellApplicationRunner}: program arguments run one command
 * non-interactively, no arguments start the interactive shell.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Configuration
public class ShellConfig
{
  /**
   * Restores the Spring Shell 3.x launch behavior: {@code cheese} starts the
   * interactive shell, {@code cheese <command> [args]} executes that single
   * command and exits.
   *
   * <p>Spring Shell 4 dropped this hybrid mode — the auto-configuration
   * (here {@code JLineShellAutoConfiguration}, since {@code spring-shell-jline}
   * is on the classpath) registers either the interactive
   * {@code JLineShellRunner} ({@code spring.shell.interactive.enabled=true},
   * the default) or the {@link NonInteractiveShellRunner} ({@code =false}),
   * never both, and the interactive runner ignores program arguments with a
   * mere log warning. There is no property to get both behaviors at once.
   *
   * <p>This bean must be named exactly {@code springShellApplicationRunner}:
   * the auto-configured runner is
   * {@code @ConditionalOnMissingBean(name = "springShellApplicationRunner")}
   * and backs off in its favor. With arguments present it executes them via a
   * locally constructed {@link NonInteractiveShellRunner}; otherwise it
   * delegates to the auto-configured interactive {@link ShellRunner}.
   *
   * <p>Arguments containing whitespace are re-quoted before being handed
   * over, because {@code NonInteractiveShellRunner} naively joins the
   * argument array with spaces — without quoting,
   * {@code cheese add-txt --data "a b"} would arrive at the command parser as
   * three separate tokens.
   */
  @Bean
  public ApplicationRunner springShellApplicationRunner(
    ShellRunner interactiveShellRunner, CommandParser commandParser,
    CommandRegistry commandRegistry)
  {
    return args ->
    {
      String[] sourceArgs = args.getSourceArgs();
      if(sourceArgs.length > 0)
      {
        String[] quoted = new String[sourceArgs.length];
        for(int i = 0; i < sourceArgs.length; i++)
        {
          String arg = sourceArgs[i];
          quoted[i] = arg.matches(".*\\s.*")
            ? "\"" + arg.replace("\"", "\\\"") + "\"" : arg;
        }
        new NonInteractiveShellRunner(commandParser, commandRegistry)
          .run(quoted);
      }
      else
      {
        interactiveShellRunner.run(sourceArgs);
      }
    };
  }

  /**
   * Overrides the Spring Shell default prompt ("shell:>") for the JLine
   * interactive shell. {@code JLineShellAutoConfiguration} injects this bean
   * (the default is {@code @ConditionalOnMissingBean}).
   */
  @Bean
  public PromptProvider promptProvider()
  {
    return () -> new AttributedString("cheese:> ",
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
  }
}

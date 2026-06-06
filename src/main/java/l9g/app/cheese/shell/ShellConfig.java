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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.jline.PromptProvider;

/**
 * Spring configuration for the interactive JLine shell.
 *
 * <p>Currently it customizes the interactive prompt by providing a
 * {@link PromptProvider} bean that replaces Spring Shell's default
 * {@code "shell:>"} with the styled {@code "cheese:> "} prompt.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Configuration
public class ShellConfig
{
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

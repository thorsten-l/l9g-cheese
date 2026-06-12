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
package l9g.app.cheese;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Spring Boot entry point for {@code cheese}, an interactive Spring Shell CLI
 * that manages DNS records in a Micetro (Men&amp;Mice) IPAM server over JSON-RPC
 * 2.0.
 * <p>
 * The class is annotated {@link SpringBootApplication @SpringBootApplication} to
 * trigger component scanning and autoconfiguration, and
 * {@link ImportRuntimeHints @ImportRuntimeHints} with {@link CaffeineRuntimeHints}
 * to register the reflection metadata Caffeine needs when running as a GraalVM
 * native image.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@SpringBootApplication
@ImportRuntimeHints(CaffeineRuntimeHints.class)
public class CheeseApplication
{

  /**
   * Application entry point; boots the Spring application context and, in the
   * presence of a real terminal, starts the interactive Spring Shell prompt.
   * <p>
   * When command-line arguments are present, the app runs in one-shot mode
   * (see {@code ShellConfig.springShellApplicationRunner}): the banner and
   * Spring Boot's startup info logging are suppressed so only the command's
   * own output reaches the console. Both must be disabled here, before
   * {@link SpringApplication#run}, because they are emitted during context
   * startup — long before any {@code ApplicationRunner} executes.
   *
   * @param args the command-line arguments passed through to
   *             {@link SpringApplication#run(String...)}
   */
  public static void main(String[] args)
  {
    SpringApplication app = new SpringApplication(CheeseApplication.class);
    if(args.length > 0)
    {
      app.setBannerMode(Banner.Mode.OFF);
      app.setLogStartupInfo(false);
    }
    app.run(args);
  }

}

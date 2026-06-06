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

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for connecting to a Micetro (Men&amp;Mice) IPAM
 * server. Bound from the {@code micetro.*} prefix (see
 * {@code application.yaml} / {@code data/config.yaml}) and consumed by
 * {@link MicetroClient} (for the API endpoint) and {@link MicetroService} (for
 * the login credentials and session-cache TTL). Lombok's {@code @Data}
 * generates the getters/setters and {@code @ToString} the string
 * representation.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Configuration
@ConfigurationProperties(prefix = "micetro")
@Data
@ToString
public class MicetroConfig
{
  /** Base URL of the Micetro JSON-RPC API endpoint. */
  private String apiUrl;

  /** Name/address of the backing Micetro server passed to the login call. */
  private String server;

  /** Login (user) name used to authenticate against Micetro. */
  private String loginName;

  /** Password used together with {@link #loginName} to authenticate. */
  private String password;

  /**
   * Whether unauthorized responses should be reported as forbidden by the
   * server-side login handling.
   */
  private boolean unauthorizedAsForbidden;

  /**
   * Time-to-live, in seconds, of the cached Micetro login session held by
   * {@link MicetroService}'s Caffeine cache.
   */
  private long sessionCacheTtl;
}

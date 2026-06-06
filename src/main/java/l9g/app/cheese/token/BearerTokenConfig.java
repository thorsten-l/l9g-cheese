/*
 * Copyright 2025 Thorsten Ludewig (t.ludewig@gmail.com).
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
package l9g.app.cheese.token;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 *
 * Configuration properties for loading bearer tokens from the application
 * configuration (e.g., YAML file). This class maps properties under the
 * {@code bearer-tokens} prefix.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Configuration
@ConfigurationProperties(prefix = "bearer-tokens")
@Data
@ToString
public class BearerTokenConfig
{
  /**
   * The configured bearer tokens, keyed by an arbitrary identifier (the
   * configuration map key). Each entry describes a single token and its
   * authorization scope.
   */
  private Map<String,BearerToken> map;

  /**
   * A single configured bearer token together with its owner metadata and the
   * authorization scope (zones and FQDNs) it is permitted to act upon.
   */
  @Data
  @ToString
  public static class BearerToken
  {
    /**
     * The secret bearer token value used to authenticate a request.
     */
    private String token;

    /**
     * The owner this token belongs to, for identification and auditing.
     */
    private String owner;

    /**
     * A free-form, human-readable description of the token's purpose.
     */
    private String description;

    /**
     * The DNS zones this token is permitted to operate on; mutating operations
     * are authorized only for zones listed here.
     */
    private List<String> permittedZones;

    /**
     * The fully-qualified domain names this token is permitted to operate on.
     */
    private List<String> permittedFqdns;

    /**
     * Whether this token is active; disabled tokens ({@code false}) must not be
     * accepted. Defaults to {@code false}.
     */
    private boolean enabled = false;
  }
}

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

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import l9g.app.cheese.jsonrpc.JsonRpcError;
import l9g.app.cheese.jsonrpc.JsonRpcRequest;
import l9g.app.cheese.jsonrpc.JsonRpcResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Thin JSON-RPC 2.0 transport over Spring's {@link RestClient} for talking to a
 * Micetro (Men&amp;Mice) IPAM server. It owns a single {@code RestClient} bound
 * to the configured API URL, auto-increments the JSON-RPC request id, and
 * (de)serializes the {@link JsonRpcRequest} / {@link JsonRpcResponse} envelope.
 * All business logic lives in {@link MicetroService}; this class only knows how
 * to issue one RPC call and surface errors.
 * <p>
 * {@code @RegisterReflectionForBinding} registers the JSON-RPC record DTOs so
 * Jackson can (de)serialize them in a GraalVM native image.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Service
@RegisterReflectionForBinding(
{
  JsonRpcRequest.class, JsonRpcResponse.class, JsonRpcError.class
})
public class MicetroClient
{
  private final RestClient restClient;

  private final AtomicInteger requestId = new AtomicInteger(0);

  /**
   * Builds the {@link RestClient} used for all RPC calls, pointing it at the
   * configured Micetro API URL and defaulting the request content type to JSON.
   *
   * @param builder the Spring-provided {@code RestClient.Builder} to configure
   *               and build from
   * @param config  the Micetro configuration supplying the API URL
   */
  public MicetroClient(RestClient.Builder builder, MicetroConfig config)
  {
    this.restClient = builder
      .baseUrl(config.getApiUrl())
      .defaultHeader(
        HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build();
  }

  /**
   * Performs a single JSON-RPC 2.0 call against Micetro. Wraps {@code method}
   * and {@code params} in a {@link JsonRpcRequest} with the next auto-generated
   * request id, POSTs it, and unwraps the {@link JsonRpcResponse}. Micetro
   * returns JSON-RPC errors with an HTTP 4xx/5xx status and the error payload in
   * the body, so the default status handling is suppressed to let the body be
   * deserialized; if the response carries an error a {@link MicetroApiException}
   * is thrown.
   *
   * @param method the Micetro JSON-RPC method name (e.g. {@code "login"},
   *              {@code "GetDNSZones"})
   * @param params the call parameters, typically a {@code LinkedHashMap} of
   *              named arguments (may be {@code null})
   * @return the JSON-RPC {@code result} object as a {@code LinkedHashMap}, or
   *         {@code null} if the response itself (or its result) was absent
   * @throws MicetroApiException if the response carries a JSON-RPC error
   */
  public LinkedHashMap<String, Object> call(String method, Object params)
  {
    JsonRpcRequest request =
      new JsonRpcRequest(method, params, requestId.incrementAndGet());
    log.trace("request={}", request);

    JsonRpcResponse<LinkedHashMap<String,Object>> response = restClient.post()
      .body(request)
      .retrieve()
      // Micetro returns JSON-RPC errors with an HTTP 4xx/5xx status and the
      // error payload in the body. Swallow the default status handling so the
      // body is still deserialized and surfaced as a MicetroApiException below.
      .onStatus(HttpStatusCode::isError, (req, res) -> { })
      .body(new ParameterizedTypeReference<JsonRpcResponse<LinkedHashMap<String,Object>>>(){});

    if(response != null && response.error() != null)
    {
      throw new MicetroApiException(response.error());
    }

    log.debug("response={}", response);
    return response != null ? response.result() : null;
  }

}

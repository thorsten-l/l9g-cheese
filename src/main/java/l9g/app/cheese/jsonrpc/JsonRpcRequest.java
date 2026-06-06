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
package l9g.app.cheese.jsonrpc;

/**
 * Immutable DTO representing a JSON-RPC 2.0 request envelope.
 * <p>
 * It carries the protocol version, the remote {@code method} to invoke, the
 * {@code params} passed to that method, and a client-assigned {@code id} used to
 * correlate the matching {@link JsonRpcResponse}.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 *
 * @param jsonrpc the JSON-RPC protocol version; always {@code "2.0"}
 * @param method  the name of the remote method to be invoked
 * @param params  the parameter values for the invoked method; may be {@code null}
 * @param id      the client-assigned request identifier used to correlate the
 *                response
 */
public record JsonRpcRequest(
  String jsonrpc, String method, Object params, int id)
  {

  /**
   * Convenience constructor that builds a request with the JSON-RPC protocol
   * version defaulted to {@code "2.0"}.
   *
   * @param method the name of the remote method to be invoked
   * @param params the parameter values for the invoked method; may be
   *               {@code null}
   * @param id     the client-assigned request identifier used to correlate the
   *               response
   */
  public JsonRpcRequest(String method, Object params, int id)
  {
    this("2.0", method, params, id);
  }

}

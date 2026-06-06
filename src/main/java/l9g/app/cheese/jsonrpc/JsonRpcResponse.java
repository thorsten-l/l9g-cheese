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
 * Immutable DTO representing a JSON-RPC 2.0 response envelope.
 * <p>
 * It carries the protocol version, the {@code id} echoed back from the matching
 * {@link JsonRpcRequest}, and exactly one of either a successful {@code result}
 * or an {@link JsonRpcError}. The response is generic in the type of the
 * {@code result} payload.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 *
 * @param <T>     the type of the successful result payload
 *
 * @param jsonrpc the JSON-RPC protocol version; always {@code "2.0"}
 * @param id      the request identifier echoed back from the originating request
 * @param result  the result payload on success; {@code null} when an
 *                {@code error} is present
 * @param error   the error details on failure; {@code null} when a
 *                {@code result} is present
 */
public record JsonRpcResponse<T>(
  String jsonrpc, int id, T result, JsonRpcError error)
  {
}

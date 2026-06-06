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
 * Immutable DTO representing the {@code error} member of a JSON-RPC 2.0
 * response envelope.
 * <p>
 * Per the JSON-RPC 2.0 specification, this object is present in a response only
 * when the corresponding request failed; it conveys a machine-readable error
 * {@code code}, a short human-readable {@code message}, and optional structured
 * {@code data} with additional details about the failure.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 *
 * @param code    the numeric JSON-RPC error code identifying the error type
 * @param message a short, human-readable description of the error
 * @param data    optional, implementation-defined value carrying additional
 *                information about the error; may be {@code null}
 */
public record JsonRpcError(int code, String message, Object data)
  {
}

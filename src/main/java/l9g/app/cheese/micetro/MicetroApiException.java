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

import l9g.app.cheese.jsonrpc.JsonRpcError;

/**
 * Unchecked exception raised when a Micetro JSON-RPC call returns an error
 * envelope instead of a result. It is thrown by {@link MicetroClient#call}
 * whenever the {@link l9g.app.cheese.jsonrpc.JsonRpcResponse} carries a
 * non-null {@link JsonRpcError}, and it carries the JSON-RPC error code
 * alongside the (inherited) error message so callers can distinguish failure
 * modes.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
public class MicetroApiException extends RuntimeException
{
  private final int code;

  /**
   * Creates an exception from a JSON-RPC error payload, using the error's
   * message as the exception message and capturing its numeric code.
   *
   * @param error the JSON-RPC error returned by Micetro; its
   *              {@link JsonRpcError#message()} becomes the exception message and
   *              its {@link JsonRpcError#code()} is stored in {@link #code}
   */
  public MicetroApiException(JsonRpcError error)
  {
    super(error.message());
    this.code = error.code();
  }

}

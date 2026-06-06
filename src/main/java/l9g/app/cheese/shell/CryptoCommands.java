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

import de.l9g.crypto.core.CryptoHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
@Component
public class CryptoCommands
{
  @Command(name = "encrypt",
           group = "Crypto Commands",
           description = "Encrypt a clear text value into a {AES256}... cipher string")
  public String encrypt(
    @Option(longName = "text",
            description = "Clear text to encrypt",
            required = true) String text)
  {
    log.debug("encrypt");
    return "\"" + text + "\" = \"" + CryptoHandler.getInstance().encrypt(text) + "\"";
  }

}

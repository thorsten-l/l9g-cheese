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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot integration test for {@link CheeseApplication}.
 * <p>
 * Annotated {@link SpringBootTest @SpringBootTest} so the full application
 * context is bootstrapped, this serves as a smoke test that all beans wire up
 * correctly.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@SpringBootTest
class CheeseApplicationTests {

	/**
	 * Verifies that the Spring application context starts up successfully. The
	 * test passes if the context loads without throwing; an empty body is
	 * sufficient because {@link SpringBootTest @SpringBootTest} performs the
	 * context initialization.
	 */
	@Test
	void contextLoads() {
	}

}

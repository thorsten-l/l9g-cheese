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

import java.security.Principal;
import l9g.app.cheese.token.BearerTokenConfig.BearerToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Spring MVC {@link HandlerMethodArgumentResolver} that resolves controller
 * parameters annotated with {@link AuthenticatedBearerToken} into the
 * {@link BearerToken} of the authenticated caller.
 * <p>
 * It looks up the current request's {@link Principal} and uses its name as the
 * key into the {@link BearerTokenConfig} map to locate the matching configured
 * token. If no principal is present, or no token is configured for the
 * principal's name, a {@link MissingOrInvalidTokenException} is thrown.
 *
 * @author Thorsten Ludewig (t.ludewig@gmail.com)
 */
@Slf4j
public class BearerTokenArgumentResolver implements
  HandlerMethodArgumentResolver
{

  private final BearerTokenConfig tokenConfig;

  /**
   * Creates a resolver backed by the given bearer-token configuration.
   *
   * @param tokenConfig the configuration holding the map of configured bearer
   *                    tokens used to resolve the authenticated caller
   */
  public BearerTokenArgumentResolver(BearerTokenConfig tokenConfig)
  {
    this.tokenConfig = tokenConfig;
  }

  /**
   * Indicates whether this resolver can handle the given method parameter.
   *
   * @param parameter the method parameter to inspect
   * @return {@code true} if the parameter is annotated with
   *         {@link AuthenticatedBearerToken} and its type is assignable to
   *         {@link BearerToken}; {@code false} otherwise
   */
  @Override
  public boolean supportsParameter(MethodParameter parameter)
  {
    return parameter.hasParameterAnnotation(AuthenticatedBearerToken.class)
      && BearerToken.class.isAssignableFrom(parameter.getParameterType());
  }

  /**
   * Resolves the annotated parameter to the {@link BearerToken} of the
   * authenticated caller.
   * <p>
   * The current request's {@link Principal} name is used as the lookup key into
   * the configured token map.
   *
   * @param parameter     the method parameter being resolved
   * @param mavContainer  the model-and-view container for the current request
   * @param webRequest    the current web request, used to obtain the user
   *                      principal
   * @param binderFactory the factory for creating data binders; not used here
   * @return the {@link BearerToken} configured for the authenticated principal
   * @throws MissingOrInvalidTokenException if there is no authenticated
   *                                        principal, or no token is configured
   *                                        for the principal's name
   */
  @Override
  public Object resolveArgument(
    MethodParameter parameter,
    ModelAndViewContainer mavContainer,
    NativeWebRequest webRequest,
    WebDataBinderFactory binderFactory
  )
  {
    Principal principal = webRequest.getUserPrincipal();
    
    log.debug("{}", principal);
    
    if(principal == null)
    {
      throw new MissingOrInvalidTokenException("anonymous");
    }

    BearerToken token = tokenConfig.getMap().get(principal.getName());
    if(token == null)
    {
      throw new MissingOrInvalidTokenException(principal.getName());
    }

    return token;
  }

}

package com.ticketsystem.api.access;

import com.ticketsystem.api.auth.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets controllers declare {@code AccessScope scope} as a handler parameter: the request-scoped
 * value {@link AccessScopeFilter} built for this request. A request without one (not signed in)
 * gets 401 - the role gates normally stop such requests first.
 */
@Configuration(proxyBeanMethods = false)
public class AccessScopeArgumentResolver
    implements HandlerMethodArgumentResolver, WebMvcConfigurer {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return AccessScope.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Object scope = request == null ? null : request.getAttribute(AccessScope.REQUEST_ATTRIBUTE);
    if (scope instanceof AccessScope s) {
      return s;
    }
    throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in.");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(this);
  }
}

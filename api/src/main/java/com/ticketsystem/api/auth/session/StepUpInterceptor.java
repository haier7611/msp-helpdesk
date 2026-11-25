package com.ticketsystem.api.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enforces {@link RequiresStepUp} before the controller method runs. The thrown error goes through
 * the normal exception handler (403 {@code step_up_required}).
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration(proxyBeanMethods = false)
public class StepUpInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  private final StepUp stepUp;

  public StepUpInterceptor(StepUp stepUp) {
    this.stepUp = stepUp;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method && needsStepUp(method)) {
      stepUp.require(request.getSession(false));
    }
    return true;
  }

  static boolean needsStepUp(HandlerMethod method) {
    return method.hasMethodAnnotation(RequiresStepUp.class)
        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresStepUp.class);
  }
}

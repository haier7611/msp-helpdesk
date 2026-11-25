package com.ticketsystem.api.auth.session;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or every method of a controller) that needs a step-up in the last 10
 * minutes; otherwise the request gets 403 {@code {"error":"step_up_required"}} and the web app's
 * step-up dialog asks for a code and retries. Actions that need it: role change, MFA reset, API key
 * creation, mailbox credentials, signature template, supporter email replies setting, Microsoft
 * sign-in settings (11c). Later items annotate their own endpoints.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresStepUp {}

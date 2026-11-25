package com.ticketsystem.api.mail;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders a {@link SystemEmail} to subject, HTML and plain text with Thymeleaf. HTML output escapes
 * every variable ({@code th:text}, {@code th:href}), which is the C5 rule that placeholder values
 * are HTML-escaped. Missing or blank variables, and links that are not http(s), are refused rather
 * than rendered as an empty or dangerous placeholder.
 */
public class SystemEmailRenderer {

  /** Variables holding links; only http(s) URLs are accepted (no javascript: or data:). */
  static final Set<String> LINK_VARIABLES = Set.of("link", "signInLink");

  private final TemplateEngine engine;
  private final Supplier<String> supportName;

  /** A fixed support name (tests). */
  public SystemEmailRenderer(String supportName) {
    this(() -> supportName);
  }

  /** The support name read at each render (Admin - Branding). */
  public SystemEmailRenderer(Supplier<String> supportName) {
    this.supportName = supportName;
    this.engine = new TemplateEngine();
    engine.addTemplateResolver(resolver("mail-templates/html/*", TemplateMode.HTML, 1));
    engine.addTemplateResolver(resolver("mail-templates/text/*", TemplateMode.TEXT, 2));
  }

  private static ClassLoaderTemplateResolver resolver(
      String pattern, TemplateMode mode, int order) {
    ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
    r.setResolvablePatterns(Set.of(pattern));
    r.setTemplateMode(mode);
    r.setCharacterEncoding(StandardCharsets.UTF_8.name());
    r.setCacheable(true);
    r.setCheckExistence(true);
    r.setOrder(order);
    return r;
  }

  public Rendered render(SystemEmail email, Map<String, String> variables) {
    for (String name : email.requiredVariables()) {
      String value = variables.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "missing template variable '" + name + "' for " + email.templateName());
      }
      if (LINK_VARIABLES.contains(name) && !isHttpUrl(value)) {
        throw new IllegalArgumentException(
            "template variable '" + name + "' must be an http(s) URL");
      }
    }
    String support = supportName.get();
    Map<String, Object> vars = new HashMap<>(variables);
    vars.put("supportName", support);
    Context ctx = new Context(Locale.UK, vars);
    String html = engine.process("mail-templates/html/" + email.templateName() + ".html", ctx);
    String text =
        engine.process("mail-templates/text/" + email.templateName() + ".txt", ctx).strip()
            + "\n\n-- \n"
            + engine.process("mail-templates/text/footer.txt", ctx).strip()
            + "\n";
    return new Rendered(email.subject(support), html, text);
  }

  static boolean isHttpUrl(String value) {
    String v = value.strip().toLowerCase(Locale.ROOT);
    return (v.startsWith("https://") || v.startsWith("http://"))
        && value.chars().noneMatch(c -> c <= ' ');
  }

  /** A rendered email; toString leaves out the bodies (they hold live links). */
  public record Rendered(String subject, String html, String text) {
    @Override
    public String toString() {
      return "Rendered[subject=" + subject + "]";
    }
  }
}

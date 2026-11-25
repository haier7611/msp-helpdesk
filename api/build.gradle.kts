plugins {
  java
  id("org.springframework.boot") version "4.1.2"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "8.11.0"
}

group = "com.ticketsystem"

version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

// Lock every resolved dependency in gradle.lockfile, so builds are repeatable and the CI
// vulnerability scan (Trivy) can read the full tree. Update with:
//   scripts/ci/api.sh dependencies --write-locks
dependencyLocking { lockAllConfigurations() }

// Spring Boot 4 modular starters (ADR-021): one starter per technology plus a matching -test
// starter. Flyway's auto-configuration now lives in spring-boot-starter-flyway, not flyway-core.
// No version overrides: Boot 4.1.2 manages patched Tomcat, Jackson and PostgreSQL JDBC.
dependencies {
  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  // Core auth (WP-02b): Spring Security 7, server-side sessions in Postgres (Spring Session
  // JDBC), Bean Validation for request bodies. BouncyCastle provides Argon2id for Spring
  // Security's Argon2PasswordEncoder; Bucket4j the in-memory rate caps (single instance).
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.bouncycastle:bcprov-jdk18on:1.86")
  implementation("com.bucket4j:bucket4j_jdk17-core:8.22.0")
  // WP-02c: QR code for TOTP enrolment, drawn as SVG path data (ZXing core only, no AWT). TOTP
  // itself is plain JDK code (auth/mfa/Totp.java).
  implementation("com.google.zxing:core:3.5.3")
  // Outbound mail (WP-02a): Spring's JavaMailSender over Angus Mail, and plain Thymeleaf (no
  // Spring MVC view integration) for the system email templates.
  implementation("org.springframework.boot:spring-boot-starter-mail")
  // Boot 4 puts Angus Mail on the runtime classpath only; SendFailure reads SMTP reply codes from
  // its org.eclipse.angus.mail.smtp exceptions, so it is needed at compile time (version managed).
  implementation("org.eclipse.angus:angus-mail")
  implementation("org.thymeleaf:thymeleaf")
  // Inbound mail HTML sanitising (WP-07a, spec section 9 / ADR-009 C5). Not managed by Boot.
  implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20261031.1")
  // Attachments (WP-04, ADR-009 C6): content-based type detection (tika-core only, no parsers) and
  // ImageIO plugins to decode WebP and robust JPEG (CMYK, odd headers) before re-encoding.
  implementation("org.apache.tika:tika-core:4.2.0")
  implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3")
  implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
  // Microsoft sign-in (WP-10): ID token signatures and Microsoft's signing keys (JWKS). Not
  // managed by Boot; 10.10 is of 2026-09-17. The OIDC code flow itself is small and lives in
  // auth/entra: the client is built from database settings (ADR-025), which the static
  // spring-boot-starter-oauth2-client registration does not fit.
  implementation("com.nimbusds:nimbus-jose-jwt:10.10")
  implementation("org.flywaydb:flyway-database-postgresql")
  // Compile scope since WP-02b: ApplicationRolePasswordCallback uses PGConnection.alterUserPassword
  // to set the application role's password with client-side SCRAM hashing.
  implementation("org.postgresql:postgresql")

  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
  testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
  testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
  testImplementation("org.springframework.boot:spring-boot-starter-security-test")
  // TestRestTemplate moved to spring-boot-resttestclient and needs spring-boot-restclient.
  testImplementation("org.springframework.boot:spring-boot-resttestclient")
  testImplementation("org.springframework.boot:spring-boot-restclient")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  // In-process SMTP server for the outbox integration tests. Its own Jakarta Mail jars are
  // excluded so only the ones from spring-boot-starter-mail are on the classpath (no duplicate
  // jakarta.mail classes).
  testImplementation("com.icegreen:greenmail:2.1.15") {
    exclude(group = "org.eclipse.angus", module = "jakarta.mail")
    exclude(group = "jakarta.mail", module = "jakarta.mail-api")
  }
  // Architecture rules (WP-03, C13): every repository method on client-owned data takes an
  // AccessScope; only the reference module builds ticket references.
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Name and version for GET /api/v1/version. No build time, so the jar stays reproducible.
springBoot {
  buildInfo {
    excludes = setOf("time")
    properties { name = "Ticket System" }
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
  maxHeapSize = "512m"
  maxParallelForks = 1
  // CI passes these through (scripts/ci/api.sh) so the Testcontainers Postgres follows the
  // CI host's container naming and port rules.
  listOf(
          "TICKETSYS_IT_CONTAINER_NAME",
          "TICKETSYS_IT_PORT",
          "TICKETSYS_IT_EXTRA_PORTS",
          "TESTCONTAINERS_HOST_OVERRIDE",
          "TESTCONTAINERS_RYUK_DISABLED",
      )
      .forEach { key -> System.getenv(key)?.let { environment(key, it) } }
  testLogging { events("passed", "skipped", "failed") }
}

// WP-12: the go-live security suite (spec 12, security tests 1-5) on its own:
// scripts/ci/api.sh securityGoLive. The same tests also run in every `build`.
tasks.register<Test>("securityGoLive") {
  description = "Runs the @SecurityGoLive suite (spec section 12, security tests 1-5)."
  group = "verification"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform { includeTags("SecurityGoLive") }
}

spotless {
  java {
    googleJavaFormat("1.28.0")
    removeUnusedImports()
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle { ktfmt("0.58") }
}

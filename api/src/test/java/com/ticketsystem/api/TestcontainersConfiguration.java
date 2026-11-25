package com.ticketsystem.api;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres 16 for integration tests.
 *
 * <p>On a CI machine used by several builds the CI job sets TICKETSYS_IT_CONTAINER_NAME (a
 * ticketsys- name the build removes by exact name) and TICKETSYS_IT_PORT (a free port in
 * 55500-55599, bound to 127.0.0.1 only). Locally neither is set and Testcontainers' defaults apply.
 *
 * <p>WP-13: a test class that runs installation-wide jobs (the SLA minute job, the daily digest)
 * with the clock moved weeks ahead can ask for its own database in the same container with
 * {@code @TestPropertySource(properties = TestcontainersConfiguration.DATABASE_PROPERTY +
 * "=<name>")}. In the shared database those jobs would pick up every ticket the other test classes
 * left behind (thousands by then) and spend minutes on them. The database is created once per JVM
 * and migrated by Flyway like the default one; classes naming the same database share it and one
 * Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16.16-alpine3.24");

  /**
   * One container per test JVM, shared by every Spring test context. CI gives the container a fixed
   * name and port, so a second context starting its own container would clash with the first.
   */
  private static PostgreSQLContainer shared;

  /** Test property naming a separate database in the shared container (see the class comment). */
  public static final String DATABASE_PROPERTY = "ticketsys.test.database";

  private static final Pattern DATABASE_NAME = Pattern.compile("ticketsys_it_[a-z0-9_]{1,30}");
  private static final Set<String> CREATED_DATABASES = new HashSet<>();

  /** Synthetic, test-only password for the application role (not a secret). */
  public static final String APP_ROLE_TEST_PASSWORD = "test-only-app-role-password";

  @Bean
  PostgreSQLContainer postgresContainer() {
    return sharedContainer();
  }

  /**
   * Two database roles, as in production (WP-02b, C14): Flyway migrates as the container's owner
   * role and the application connects as the restricted {@code ticketsys_app} role from migration
   * V5 (ApplicationRolePasswordCallback sets its password from the datasource password).
   */
  @Bean
  DynamicPropertyRegistrar databaseRoles(PostgreSQLContainer container, Environment env) {
    String database = env.getProperty(DATABASE_PROPERTY);
    String url = database == null ? container.getJdbcUrl() : databaseUrl(container, database);
    return registry -> {
      registry.add("spring.datasource.url", () -> url);
      registry.add("spring.datasource.username", () -> "ticketsys_app");
      registry.add("spring.datasource.password", () -> APP_ROLE_TEST_PASSWORD);
      registry.add("spring.flyway.user", container::getUsername);
      registry.add("spring.flyway.password", container::getPassword);
    };
  }

  /** A JdbcTemplate on the owner (Flyway) role, for checks the application role may not do. */
  public static JdbcTemplate ownerJdbc() {
    PostgreSQLContainer c = sharedContainer();
    DataSource ds = new DriverManagerDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    return new JdbcTemplate(ds);
  }

  /**
   * The JDBC URL of database {@code name} in the shared container, creating it on first use (as the
   * owner role, so Flyway can migrate it). Names must match {@code ticketsys_it_[a-z0-9_]+}.
   */
  static synchronized String databaseUrl(PostgreSQLContainer container, String name) {
    if (!DATABASE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("test database name must match " + DATABASE_NAME);
    }
    if (CREATED_DATABASES.add(name)) {
      ownerJdbc().execute("CREATE DATABASE " + name);
    }
    String url = container.getJdbcUrl();
    String path = "/" + container.getDatabaseName();
    int at = url.indexOf(path, url.indexOf("//") + 2);
    if (at < 0) {
      throw new IllegalStateException("unexpected Testcontainers JDBC URL shape");
    }
    return url.substring(0, at) + "/" + name + url.substring(at + path.length());
  }

  /** The shared container, for tests that start the application themselves (break-glass). */
  public static synchronized PostgreSQLContainer sharedContainer() {
    if (shared == null) {
      shared = newContainer();
      shared.start();
    }
    return shared;
  }

  private static PostgreSQLContainer newContainer() {
    PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES);
    String name = System.getenv("TICKETSYS_IT_CONTAINER_NAME");
    String port = System.getenv("TICKETSYS_IT_PORT");
    if (isSet(name) || isSet(port)) {
      container.withCreateContainerCmdModifier(
          cmd -> {
            if (isSet(name)) {
              cmd.withName(name);
            }
            if (isSet(port)) {
              cmd.getHostConfig()
                  .withPublishAllPorts(false)
                  .withPortBindings(
                      new PortBinding(
                          Ports.Binding.bindIpAndPort("127.0.0.1", Integer.parseInt(port)),
                          ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)));
            }
          });
    }
    return container;
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }
}

package com.ticketsystem.api.mail;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Wires the outbound mail module from the validated environment settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MailConfiguration.class);

  /** Fails startup on invalid settings; the message names the variable, never its value. */
  @Bean
  MailSettings mailSettings(MailProperties properties) {
    MailSettings settings = MailSettings.from(properties);
    if (settings.enabled()) {
      log.info(
          "Outbound mail: SMTP {}:{} ({}), from {}, authentication {}",
          settings.host(),
          settings.port(),
          settings.security(),
          settings.fromAddress(),
          settings.username() == null ? "off" : "on");
    }
    return settings;
  }

  /**
   * The worker sends all mail (ticket mail and account and security mail) through the
   * admin-configured mailbox when one is set up for sending, otherwise through the environment
   * sender.
   */
  @Bean
  OutboxWorker outboxWorker(
      OutboxRepository repository,
      MailSettings settings,
      ObjectProvider<ConfiguredSender> configured) {
    MailTransport transport = settings.enabled() ? new SmtpMailTransport(settings) : null;
    return new OutboxWorker(
        repository,
        transport,
        configured.getIfAvailable(),
        account -> new SmtpMailTransport(account, settings),
        settings,
        Clock.systemUTC());
  }

  @Bean
  SystemEmailRenderer systemEmailRenderer(SupportName supportName) {
    return new SystemEmailRenderer(supportName);
  }

  /** Nudges the worker once the transaction that queued mail has committed (never on rollback). */
  @Component
  static class OutboxCommitListener {
    private final OutboxWorker worker;

    OutboxCommitListener(OutboxWorker worker) {
      this.worker = worker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterCommit(MailOutbox.OutboxMessageQueued event) {
      worker.nudge();
    }
  }
}

package com.ticketsystem.api.mail.intake.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.auth.session.StepUp;
import com.ticketsystem.api.mail.intake.EmailRulesService;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockCheck;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockRuleRequest;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockRuleView;
import com.ticketsystem.api.mail.intake.EmailRulesService.BlockedMailView;
import com.ticketsystem.api.mail.intake.EmailRulesService.SenderRuleRequest;
import com.ticketsystem.api.mail.intake.EmailRulesService.SenderRuleView;
import com.ticketsystem.api.mail.intake.InboundRouter.ReplyMode;
import com.ticketsystem.api.mail.intake.MailboxService;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxView;
import com.ticketsystem.api.mail.intake.MailboxService.ProbeStatus;
import com.ticketsystem.api.mail.intake.MailboxService.TestResult;
import com.ticketsystem.api.organisation.VerifiedDomains;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Email backend: mailboxes with test connection, the "Supporter replies by email" setting,
 * the block list and Blocked mail log, and sender rules. The screens are Role gate: the {@code
 * /api/v1/admin} tree (agents and customers get 403); every service checks the admin scope again.
 * Step-up for mailbox credentials and the reply setting, and for a block rule that hits a client's
 * verified domain, a saved contact or a supporter.
 */
@RestController
@RequestMapping("/api/v1/admin/email")
public class AdminEmailController {

  public record MailboxBody(
      @NotNull @Size(max = 100) String name,
      @NotNull @Size(max = 320) String address,
      boolean enabled,
      @NotNull @Size(max = 253) String imapHost,
      Integer imapPort,
      @Size(max = 10) String imapSecurity,
      @Size(max = 200) String imapFolder,
      @Size(max = 10) String authType,
      @NotNull @Size(max = 320) String username,
      @Size(max = 4000) String secret,
      @Size(max = 100) String oauthTenantId,
      @Size(max = 500) String oauthTokenUrl,
      @Size(max = 200) String oauthClientId,
      @Size(max = 500) String oauthScope,
      @Size(max = 10) List<@Size(max = 253) String> trustedAuthservIds,
      @Size(max = 253) String smtpHost,
      Integer smtpPort,
      @Size(max = 10) String smtpSecurity,
      Boolean smtpSameSignIn,
      @Size(max = 320) String smtpUsername,
      @Size(max = 4000) String smtpSecret) {

    /** Never prints a secret, only whether one was sent. */
    @Override
    public String toString() {
      return "MailboxBody[secret="
          + (secret == null ? "unchanged" : "(set)")
          + ", smtpSecret="
          + (smtpSecret == null ? "unchanged" : "(set)")
          + "]";
    }

    MailboxRequest toRequest() {
      return new MailboxRequest(
          name,
          address,
          enabled,
          imapHost,
          imapPort,
          imapSecurity,
          imapFolder,
          authType,
          username,
          secret,
          oauthTenantId,
          oauthTokenUrl,
          oauthClientId,
          oauthScope,
          trustedAuthservIds,
          smtpHost,
          smtpPort,
          smtpSecurity,
          smtpSameSignIn,
          smtpUsername,
          smtpSecret);
    }
  }

  public record ReplyModeBody(@NotNull @Size(max = 20) String supporterReplies) {}

  public record ReplyModeView(ReplyMode supporterReplies) {}

  public record BlockRuleBody(
      @NotNull @Size(max = 10) String kind,
      @NotNull @Size(max = 330) String value,
      boolean confirm) {}

  public record SenderRuleBody(
      @NotNull @Size(max = 10) String kind,
      @NotNull @Size(max = 330) String value,
      @NotNull Long organisationId,
      @NotNull Long requestTypeId) {}

  private final MailboxService mailboxes;
  private final EmailRulesService rules;
  private final StepUp stepUp;
  private final VerifiedDomains domains;

  public AdminEmailController(
      MailboxService mailboxes, EmailRulesService rules, StepUp stepUp, VerifiedDomains domains) {
    this.mailboxes = mailboxes;
    this.rules = rules;
    this.stepUp = stepUp;
    this.domains = domains;
  }

  // Mailboxes ------------------------------------------------------------------------------------

  @GetMapping("/mailboxes")
  List<MailboxView> mailboxes(AccessScope scope) {
    return mailboxes.list(scope);
  }

  @GetMapping("/mailboxes/{id}")
  MailboxView mailbox(AccessScope scope, @PathVariable long id) {
    return mailboxes.get(scope, id);
  }

  @PostMapping("/mailboxes")
  @RequiresStepUp
  ResponseEntity<MailboxView> createMailbox(
      AccessScope scope, @Validated @RequestBody MailboxBody body, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mailboxes.create(scope, body.toRequest(), request.getRemoteAddr()));
  }

  @PutMapping("/mailboxes/{id}")
  @RequiresStepUp
  MailboxView updateMailbox(
      AccessScope scope,
      @PathVariable long id,
      @Validated @RequestBody MailboxBody body,
      HttpServletRequest request) {
    return mailboxes.update(scope, id, body.toRequest(), request.getRemoteAddr());
  }

  @DeleteMapping("/mailboxes/{id}")
  @RequiresStepUp
  ResponseEntity<Void> deleteMailbox(
      AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    mailboxes.delete(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/mailboxes/{id}/test")
  TestResult testMailbox(AccessScope scope, @PathVariable long id) {
    return mailboxes.test(scope, id);
  }

  @GetMapping("/mailboxes/{id}/test/{probeId}")
  ProbeStatus probe(AccessScope scope, @PathVariable long id, @PathVariable long probeId) {
    return mailboxes.probeStatus(scope, id, probeId);
  }

  // Supporter replies by email -------------------------------------------------------------------

  @GetMapping("/settings")
  ReplyModeView settings(AccessScope scope) {
    return new ReplyModeView(rules.replyMode(scope));
  }

  @PutMapping("/settings")
  @RequiresStepUp
  ReplyModeView updateSettings(
      AccessScope scope, @Validated @RequestBody ReplyModeBody body, HttpServletRequest request) {
    return new ReplyModeView(
        rules.setReplyMode(scope, body.supporterReplies(), request.getRemoteAddr()));
  }

  // Block list -----------------------------------------------------------------------------------

  @GetMapping("/block-rules")
  List<BlockRuleView> blockRules(AccessScope scope) {
    return rules.blockRules(scope);
  }

  /**
   * Adds a block rule. When it would block a client's verified domain, a saved contact or a
   * supporter, the first call answers 409 {@code confirmation_required} with the warning; the admin
   * repeats it with {@code confirm: true} after a step-up.
   */
  @PostMapping("/block-rules")
  ResponseEntity<?> addBlockRule(
      AccessScope scope, @Validated @RequestBody BlockRuleBody body, HttpServletRequest request) {
    BlockCheck check = rules.checkBlockRule(scope, new BlockRuleRequest(body.kind(), body.value()));
    if (check.needsConfirmation()) {
      if (!body.confirm()) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                Map.of(
                    "error",
                    "confirmation_required",
                    "message",
                    check.warning() + " Confirm to block it anyway."));
      }
      stepUp.require(request.getSession(false));
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(rules.addBlockRule(scope, check, request.getRemoteAddr()));
  }

  @DeleteMapping("/block-rules/{id}")
  ResponseEntity<Void> removeBlockRule(
      AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    rules.removeBlockRule(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/blocked-mail")
  List<BlockedMailView> blockedMail(AccessScope scope) {
    return rules.blockedMail(scope);
  }

  @PostMapping("/blocked-mail/{id}/restore")
  Map<String, Long> restore(AccessScope scope, @PathVariable long id) {
    return Map.of("ticketId", rules.restore(scope, id));
  }

  // Sender rules ---------------------------------------------------------------------------------

  @GetMapping("/sender-rules")
  List<SenderRuleView> senderRules(AccessScope scope) {
    return rules.senderRules(scope);
  }

  @PostMapping("/sender-rules")
  ResponseEntity<SenderRuleView> addSenderRule(
      AccessScope scope, @Validated @RequestBody SenderRuleBody body, HttpServletRequest request) {
    if (body.organisationId() == null || body.requestTypeId() == null) {
      throw ApiException.invalidRequest("Choose an organisation and a request type.");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            rules.addSenderRule(
                scope,
                new SenderRuleRequest(
                    body.kind(), body.value(), body.organisationId(), body.requestTypeId()),
                request.getRemoteAddr()));
  }

  // Public email domains: maintained in the repository, shown read-only ------

  @GetMapping("/public-domains")
  List<String> publicDomains(AccessScope scope) {
    scope.requireAdmin();
    return domains.publicDomains();
  }

  @DeleteMapping("/sender-rules/{id}")
  ResponseEntity<Void> removeSenderRule(
      AccessScope scope, @PathVariable long id, HttpServletRequest request) {
    rules.removeSenderRule(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }
}

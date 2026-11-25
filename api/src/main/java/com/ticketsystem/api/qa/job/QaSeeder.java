package com.ticketsystem.api.qa.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.access.UserAccessService;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.PasswordPolicy;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.auth.mfa.Totp;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.organisation.OrganisationService.Details;
import com.ticketsystem.api.qa.QaAuditEvent;
import com.ticketsystem.api.qa.job.QaCredentials.Entry;
import com.ticketsystem.api.qa.job.clientdata.QaSeedRepository;
import com.ticketsystem.api.qa.job.clientdata.QaSeedRepository.Kind;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.RequestTypeService;
import com.ticketsystem.api.ticket.RequestTypeService.RequestTypeOption;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the synthetic QA data set through the real services, under a SYSTEM scope, and records
 * everything it creates in the {@code qa_seed} marker table so {@link QaWiper} can remove exactly
 * that. Idempotent: every entity is looked up by its marker first. Passwords are random and exist
 * only in the credentials sheet (a mode-600 file); an existing account's password is rotated only
 * with {@code resetPasswords}.
 */
@Component
public class QaSeeder {

  static final String ADMIN_EMAIL = "qa-admin@msp.example";
  static final String ALEX_EMAIL = "alex@msp.example";
  static final String RILEY_EMAIL = "riley@msp.example";
  static final String CLIENT_IP = "qa-seed";

  private record OrgSpec(String name, String prefix, String domain) {}

  private record ClientSpec(String email, String name, String prefix) {}

  private record TicketSpec(
      String key,
      String prefix,
      String reporter,
      String type,
      String subject,
      TicketStatus status,
      String assignee) {}

  static final List<OrgSpec> ORGS =
      List.of(
          new OrgSpec("Acme Dental", "ACD", "acmedental.example"),
          new OrgSpec("Northwind Logistics", "NWL", "northwind.example"),
          new OrgSpec("Bluefin Architects", "BFA", "bluefin.example"),
          new OrgSpec("QA Testers", "QA", "qa.example"));

  static final List<ClientSpec> CLIENTS =
      List.of(
          new ClientSpec("dana.reyes@acmedental.example", "Dana Reyes", "ACD"),
          new ClientSpec("sam.okoye@acmedental.example", "Sam Okoye (CC colleague)", "ACD"),
          new ClientSpec("priya.nair@northwind.example", "Priya Nair", "NWL"),
          new ClientSpec("tomas.berg@northwind.example", "Tomas Berg", "NWL"),
          new ClientSpec("lena.ito@bluefin.example", "Lena Ito", "BFA"),
          new ClientSpec("tester1@qa.example", "QA Tester 1", "QA"),
          new ClientSpec("tester2@qa.example", "QA Tester 2", "QA"),
          new ClientSpec("tester3@qa.example", "QA Tester 3", "QA"),
          new ClientSpec("tester4@qa.example", "QA Tester 4", "QA"),
          new ClientSpec("tester5@qa.example", "QA Tester 5", "QA"));

  static final List<TicketSpec> TICKETS =
      List.of(
          new TicketSpec(
              "T01",
              "ACD",
              "dana.reyes@acmedental.example",
              "Computers and devices",
              "Reception printer will not print",
              TicketStatus.OPEN,
              ALEX_EMAIL),
          new TicketSpec(
              "T02",
              "ACD",
              "dana.reyes@acmedental.example",
              "Email and Microsoft 365",
              "Cannot sign in to Outlook on the surgery PC",
              TicketStatus.PENDING_ON_CLIENT,
              ALEX_EMAIL),
          new TicketSpec(
              "T03",
              "ACD",
              "sam.okoye@acmedental.example",
              "New starter or leaver",
              "New starter: dental nurse joins on Monday",
              TicketStatus.NEW,
              null),
          new TicketSpec(
              "T04",
              "ACD",
              "sam.okoye@acmedental.example",
              "Computers and devices",
              "Printer still not printing (second report)",
              TicketStatus.NEW,
              null),
          new TicketSpec(
              "T05",
              "NWL",
              "priya.nair@northwind.example",
              "Network and internet",
              "Wi-Fi drops in the warehouse office",
              TicketStatus.PENDING_ON_VENDOR,
              ALEX_EMAIL),
          new TicketSpec(
              "T06",
              "NWL",
              "tomas.berg@northwind.example",
              "Software and applications",
              "Scanner driver update on the dispatch PC",
              TicketStatus.RESOLVED,
              ALEX_EMAIL),
          new TicketSpec(
              "T07",
              "NWL",
              "priya.nair@northwind.example",
              "Security concern",
              "Suspicious email asking for a payment",
              TicketStatus.OPEN,
              ALEX_EMAIL),
          new TicketSpec(
              "T08",
              "BFA",
              "lena.ito@bluefin.example",
              "Software and applications",
              "CAD licence will not activate",
              TicketStatus.OPEN,
              RILEY_EMAIL),
          new TicketSpec(
              "T09",
              "BFA",
              "lena.ito@bluefin.example",
              "Account and access",
              "Shared drive access for a new intern",
              TicketStatus.NEW,
              null),
          new TicketSpec(
              "T10",
              "BFA",
              "lena.ito@bluefin.example",
              "Computers and devices",
              "Replace the old plotter workstation",
              TicketStatus.CLOSED,
              RILEY_EMAIL));

  private static final String PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final QaSeedRepository markers;
  private final OrganisationService organisations;
  private final UserRepository users;
  private final PasswordPolicy policy;
  private final PasswordEncoder encoder;
  private final MfaService mfa;
  private final UserAccessService access;
  private final PrincipalAccessReader principals;
  private final TicketService tickets;
  private final RequestTypeService requestTypes;
  private final InternalNoteService notes;
  private final AttachmentService attachments;
  private final TicketParticipantRepository participants;
  private final AuditService audit;
  private final Clock clock;

  public QaSeeder(
      QaSeedRepository markers,
      OrganisationService organisations,
      UserRepository users,
      PasswordPolicy policy,
      PasswordEncoder encoder,
      MfaService mfa,
      UserAccessService access,
      PrincipalAccessReader principals,
      TicketService tickets,
      RequestTypeService requestTypes,
      InternalNoteService notes,
      AttachmentService attachments,
      TicketParticipantRepository participants,
      AuditService audit,
      Clock clock) {
    this.markers = markers;
    this.organisations = organisations;
    this.users = users;
    this.policy = policy;
    this.encoder = encoder;
    this.mfa = mfa;
    this.access = access;
    this.principals = principals;
    this.tickets = tickets;
    this.requestTypes = requestTypes;
    this.notes = notes;
    this.attachments = attachments;
    this.participants = participants;
    this.audit = audit;
    this.clock = clock;
  }

  /** Per-run state. */
  private final class Run {
    final AccessScope system = AccessScope.system("qa seed (ADR-030, host command)");
    final QaCredentials sheet;
    final boolean resetPasswords;
    final Map<String, Long> orgIds = new HashMap<>();
    final Map<String, Long> userIds = new HashMap<>();
    final Map<String, Long> ticketIds = new HashMap<>();
    final Set<String> newUsers = new java.util.HashSet<>();
    int created;

    Run(QaCredentials sheet, boolean resetPasswords) {
      this.sheet = sheet;
      this.resetPasswords = resetPasswords;
    }
  }

  /** Seeds (or completes) the QA data; returns a process exit code. Prints counts only. */
  public int seed(Path credentialsFile, boolean resetPasswords, PrintStream out) {
    QaCredentials sheet;
    try {
      sheet = QaCredentials.read(credentialsFile);
    } catch (IOException e) {
      out.println("qa-seed: could not read the credentials file. Nothing changed.");
      return 1;
    }
    Run run = new Run(sheet, resetPasswords);
    int code = 0;
    try {
      doSeed(run);
    } catch (RuntimeException e) {
      out.println("qa-seed: stopped - " + safeMessage(e));
      code = 1;
    } finally {
      // Passwords exist only here: write the sheet even when a later step failed.
      try {
        sheet.write(credentialsFile, clock.instant());
      } catch (IOException e) {
        out.println(
            "qa-seed: COULD NOT WRITE the credentials file - run again with --reset-passwords");
        code = 1;
      }
    }
    try {
      audit.record(
          QaAuditEvent.QA_SEED,
          null,
          null,
          CLIENT_IP,
          Map.of("rows", run.created, "kind", resetPasswords ? "reset_passwords" : "seed"));
    } catch (RuntimeException e) {
      out.println("qa-seed: could not write the audit event");
      code = 1;
    }
    out.println(
        "qa-seed: "
            + (code == 0 ? "done" : "finished with errors")
            + "; created "
            + run.created
            + " new rows; credentials file updated (mode 600, contents not shown).");
    return code;
  }

  private void doSeed(Run run) {
    ensureUser(run, ADMIN_EMAIL, "QA Admin", Role.ADMIN, null, "QA Admin");
    AccessScope admin = scopeOf(run.userIds.get(ADMIN_EMAIL));
    for (OrgSpec o : ORGS) {
      ensureOrganisation(run, admin, o);
    }
    for (ClientSpec c : CLIENTS) {
      ensureUser(run, c.email(), c.name(), Role.CUSTOMER, c.prefix(), orgName(c.prefix()));
    }
    ensureUser(run, ALEX_EMAIL, "Alex (engineer, all organisations)", Role.AGENT, null, "All");
    ensureUser(run, RILEY_EMAIL, "Riley (engineer, Bluefin only)", Role.AGENT, null, "BFA only");
    long alex = run.userIds.get(ALEX_EMAIL);
    long riley = run.userIds.get(RILEY_EMAIL);
    if (run.newUsers.contains(ALEX_EMAIL)) {
      access.setAgentAccess(admin, alex, true, Set.of(), CLIENT_IP);
    }
    if (run.newUsers.contains(RILEY_EMAIL)) {
      access.setAgentAccess(admin, riley, false, Set.of(run.orgIds.get("BFA")), CLIENT_IP);
    }
    ensureMfa(run, ADMIN_EMAIL);
    ensureMfa(run, ALEX_EMAIL);
    ensureMfa(run, RILEY_EMAIL);

    Map<String, Long> typeIds = new HashMap<>();
    for (RequestTypeOption t : requestTypes.forAgents(run.system)) {
      typeIds.put(t.name(), t.id());
    }
    for (TicketSpec t : TICKETS) {
      ensureTicket(run, t, typeIds);
    }
    ensureCc(run);
    ensureDuplicate(run);
    ensureNote(run);
  }

  // ---------------------------------------------------------------------------------------------
  // Accounts

  private Entry ensureUser(
      Run run, String email, String name, Role role, String orgPrefix, String orgLabel) {
    var marked = markers.find(run.system, Kind.USER, email);
    Instant now = clock.instant();
    Entry existing = run.sheet.get(email);
    if (marked.isPresent()) {
      long id = marked.get().refId();
      run.userIds.put(email, id);
      List<String> codes = existing == null ? List.of() : existing.codes();
      if (run.resetPasswords) {
        // Only an explicit reset changes an existing password.
        String pw = newPassword(email);
        users.changePassword(id, encoder.encode(pw), now);
        Entry e = new Entry(email, pw, role.name(), orgLabel, codes);
        run.sheet.put(e);
        return e;
      }
      if (existing == null) {
        // The account exists but the sheet lost it: say so, never guess a password.
        Entry e = new Entry(email, QaCredentials.UNKNOWN_PASSWORD, role.name(), orgLabel, codes);
        run.sheet.put(e);
        return e;
      }
      return existing;
    }
    if (users.findByEmail(email).isPresent()) {
      throw new IllegalStateException(
          "an account with a QA address already exists and was not created by the seed");
    }
    Long orgId = orgPrefix == null ? null : run.orgIds.get(orgPrefix);
    String pw = newPassword(email);
    long id =
        users.insertActive(
            email, name, role, role == Role.CUSTOMER ? orgId : null, encoder.encode(pw), now);
    markers.mark(run.system, Kind.USER, id, email, now);
    run.userIds.put(email, id);
    run.newUsers.add(email);
    run.created++;
    Entry e = new Entry(email, pw, role.name(), orgLabel, List.of());
    run.sheet.put(e);
    return e;
  }

  private String newPassword(String email) {
    for (int attempt = 0; attempt < 10; attempt++) {
      StringBuilder sb = new StringBuilder(24);
      for (int i = 0; i < 24; i++) {
        sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
      }
      String candidate = sb.toString();
      if (policy.check(candidate, email).isEmpty()) {
        return candidate;
      }
    }
    throw new IllegalStateException("could not generate an acceptable password");
  }

  /** Enrols two-step sign-in for a supporter and keeps the 10 recovery codes (shown once). */
  private void ensureMfa(Run run, String email) {
    long id = run.userIds.get(email);
    Entry entry = run.sheet.get(email);
    AppUser user = users.findById(id).orElseThrow();
    if (!mfa.isEnrolled(id)) {
      MfaService.Enrolment enrolment = mfa.startEnrolment(user, CLIENT_IP);
      byte[] secret = base32Decode(enrolment.manualKey());
      try {
        String code = Totp.codeAt(secret, Totp.stepAt(clock.instant()));
        List<String> codes = mfa.confirmEnrolment(user, code, null, false, CLIENT_IP);
        run.sheet.put(
            new Entry(
                email, entry.password(), entry.role(), entry.organisation(), List.copyOf(codes)));
        run.created++;
      } finally {
        java.util.Arrays.fill(secret, (byte) 0);
      }
    } else if (entry.codes().isEmpty()) {
      // Enrolled earlier but the sheet lost the codes: issue a fresh set.
      List<String> codes = mfa.regenerateRecoveryCodes(user, CLIENT_IP);
      run.sheet.put(
          new Entry(
              email, entry.password(), entry.role(), entry.organisation(), List.copyOf(codes)));
    }
  }

  static byte[] base32Decode(String s) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    String in = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : in.toCharArray()) {
      int v = alphabet.indexOf(c);
      if (v < 0) {
        throw new IllegalArgumentException("bad base32");
      }
      buffer = (buffer << 5) | v;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out.write((buffer >> bits) & 0xFF);
      }
    }
    return out.toByteArray();
  }

  // ---------------------------------------------------------------------------------------------
  // Organisations

  private void ensureOrganisation(Run run, AccessScope admin, OrgSpec o) {
    var marked = markers.find(run.system, Kind.ORGANISATION, o.prefix());
    if (marked.isPresent()) {
      run.orgIds.put(o.prefix(), marked.get().refId());
      return;
    }
    long id =
        organisations
            .create(
                admin,
                new Details(o.name(), o.prefix(), List.of(o.domain()), "Asia/Ho_Chi_Minh", null),
                CLIENT_IP,
                () -> {})
            .id();
    markers.mark(run.system, Kind.ORGANISATION, id, o.prefix(), clock.instant());
    run.orgIds.put(o.prefix(), id);
    run.created++;
  }

  private static String orgName(String prefix) {
    return ORGS.stream().filter(o -> o.prefix().equals(prefix)).findFirst().orElseThrow().name();
  }

  // ---------------------------------------------------------------------------------------------
  // Tickets

  private void ensureTicket(Run run, TicketSpec t, Map<String, Long> typeIds) {
    var marked = markers.find(run.system, Kind.TICKET, t.key());
    if (marked.isPresent()) {
      run.ticketIds.put(t.key(), marked.get().refId());
      return;
    }
    Long typeId = typeIds.get(t.type());
    if (typeId == null) {
      throw new IllegalStateException("request type not found: " + t.type());
    }
    long org = run.orgIds.get(t.prefix());
    Long assignee = t.assignee() == null ? null : run.userIds.get(t.assignee());
    Created created =
        tickets.create(
            run.system,
            new NewTicket(
                org,
                run.userIds.get(t.reporter()),
                null,
                typeId,
                null,
                null,
                null,
                TicketSource.PORTAL,
                t.subject(),
                "<p>Synthetic QA ticket " + t.key() + ". " + t.subject() + ".</p>",
                List.of(),
                null,
                assignee,
                null,
                null));
    long id = created.ticketId();
    markers.mark(run.system, Kind.TICKET, id, t.key(), clock.instant());
    run.ticketIds.put(t.key(), id);
    run.created++;
    switch (t.status()) {
      case NEW -> {}
      case CLOSED -> {
        tickets.changeStatus(run.system, id, TicketStatus.RESOLVED);
        tickets.changeStatus(run.system, id, TicketStatus.CLOSED, "RESOLVED_NO_FOLLOW_UP", null);
      }
      default -> tickets.changeStatus(run.system, id, t.status());
    }
  }

  /** Sam is a CC colleague on Dana's printer ticket. */
  private void ensureCc(Run run) {
    long ticket = run.ticketIds.get("T01");
    long sam = run.userIds.get("sam.okoye@acmedental.example");
    // add() is a no-op when the participant is already there.
    participants.add(
        run.system,
        run.orgIds.get("ACD"),
        ticket,
        sam,
        "sam.okoye@acmedental.example",
        true,
        clock.instant());
  }

  /** T04 is a merged duplicate of T01 (same organisation). */
  private void ensureDuplicate(Run run) {
    String key = "T04-dup";
    if (markers.find(run.system, Kind.STEP, key).isPresent()) {
      return;
    }
    long source = run.ticketIds.get("T04");
    long target = run.ticketIds.get("T01");
    tickets.markDuplicate(run.system, source, target);
    markers.mark(run.system, Kind.STEP, source, key, clock.instant());
    run.created++;
  }

  /** One internal note with an image on T02, written as Alex. */
  private void ensureNote(Run run) {
    String key = "T02-note";
    if (markers.find(run.system, Kind.NOTE, key).isPresent()) {
      return;
    }
    AccessScope alex = scopeOf(run.userIds.get(ALEX_EMAIL));
    var upload = attachments.upload(alex, "qa-screenshot.png", samplePng());
    long noteId =
        notes.addNote(
            alex,
            run.ticketIds.get("T02"),
            "<p>Internal: the profile looks corrupt, screenshot attached.</p><p><img"
                + " src=\"attachment:"
                + upload.id()
                + "\"></p>",
            List.of(upload.id()));
    markers.mark(run.system, Kind.NOTE, noteId, key, clock.instant());
    run.created++;
  }

  private AccessScope scopeOf(long userId) {
    return principals.read(userId).orElseThrow().toScope();
  }

  private static byte[] samplePng() {
    BufferedImage img = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setColor(new Color(250, 235, 160));
      g.fillRect(0, 0, 96, 64);
      g.setColor(new Color(60, 60, 60));
      g.drawRect(4, 4, 87, 55);
      g.drawLine(4, 4, 91, 59);
    } finally {
      g.dispose();
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(img, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The exception text only for the known, content-free cases; otherwise just the type. */
  private static String safeMessage(RuntimeException e) {
    return e.getClass().getSimpleName();
  }

  /** Visible for tests: the seeded e-mail addresses. */
  static List<String> allEmails() {
    List<String> all = new ArrayList<>();
    all.add(ADMIN_EMAIL);
    all.add(ALEX_EMAIL);
    all.add(RILEY_EMAIL);
    CLIENTS.forEach(c -> all.add(c.email()));
    return all;
  }
}

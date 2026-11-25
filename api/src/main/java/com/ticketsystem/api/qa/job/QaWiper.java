package com.ticketsystem.api.qa.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.qa.QaAuditEvent;
import com.ticketsystem.api.qa.job.clientdata.QaSeedRepository;
import com.ticketsystem.api.qa.job.clientdata.QaSeedRepository.Kind;
import com.ticketsystem.api.qa.job.clientdata.QaSeedRepository.Marker;
import com.ticketsystem.api.retention.clientdata.RetentionRepository;
import com.ticketsystem.api.retention.clientdata.RetentionRepository.BlobKeys;
import com.ticketsystem.api.ticket.attachment.BlobStore;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes what {@link QaSeeder} created and nothing else. The marker table is the only source of
 * what may be deleted: seeded users and organisations by id, seeded tickets by id. Tickets that
 * testers raised in a seeded organisation (the bug reports) are kept by default, and with them the
 * organisation and the accounts they reference (a foreign key stops the delete); {@code
 * --include-tester-data} removes those tickets too. The real admin is never in the marker table,
 * and a seeded admin is kept if it would leave no other active admin.
 */
@Component
public class QaWiper {

  private final QaSeedRepository markers;
  private final RetentionRepository retention;
  private final BlobStore blobs;
  private final UserRepository users;
  private final AuditService audit;
  private final TransactionTemplate tx;

  public QaWiper(
      QaSeedRepository markers,
      RetentionRepository retention,
      BlobStore blobs,
      UserRepository users,
      AuditService audit,
      TransactionTemplate tx) {
    this.markers = markers;
    this.retention = retention;
    this.blobs = blobs;
    this.users = users;
    this.audit = audit;
    this.tx = tx;
  }

  /** Wipes the seeded data; returns a process exit code. Prints counts only. */
  public int wipe(boolean includeTesterData, PrintStream out) {
    AccessScope system = AccessScope.system("qa wipe (ADR-030, host command)");
    List<Marker> orgMarkers = markers.list(system, Kind.ORGANISATION);
    List<Marker> userMarkers = markers.list(system, Kind.USER);
    if (orgMarkers.isEmpty() && userMarkers.isEmpty()) {
      out.println("qa-wipe: nothing was created by the seed. Nothing changed.");
      return 0;
    }
    int removed = 0;

    // 1. Tickets: the seeded ones, plus every ticket in a seeded organisation only on request.
    Set<Long> ticketIds = new LinkedHashSet<>();
    markers.list(system, Kind.TICKET).forEach(m -> ticketIds.add(m.refId()));
    List<Long> orgIds = orgMarkers.stream().map(Marker::refId).toList();
    List<Long> inSeededOrgs = markers.ticketIdsInOrganisations(system, orgIds);
    long keptTickets = inSeededOrgs.stream().filter(id -> !ticketIds.contains(id)).count();
    if (includeTesterData) {
      ticketIds.addAll(inSeededOrgs);
      keptTickets = 0;
    }
    List<Long> ticketList = new ArrayList<>(ticketIds);
    List<BlobKeys> files = retention.attachmentFiles(system, ticketList);
    int tickets = deleteInTx(() -> markers.deleteTickets(system, ticketList));
    removed += tickets;
    deleteBlobs(files);
    for (Long id : ticketList) {
      markers.unmark(system, Kind.TICKET, id);
    }
    markers.list(system, Kind.NOTE).forEach(m -> markers.unmark(system, Kind.NOTE, m.refId()));
    markers.list(system, Kind.STEP).forEach(m -> markers.unmark(system, Kind.STEP, m.refId()));

    // 2. Mail queued to the seeded addresses.
    List<String> emails = userMarkers.stream().map(Marker::refKey).toList();
    removed += deleteInTx(() -> markers.deleteOutboxTo(system, emails));

    // 3. Accounts, one by one: a foreign key from data the seed did not create keeps the account.
    List<Long> seededIds = userMarkers.stream().map(Marker::refId).toList();
    int keptUsers = 0;
    for (Marker m : userMarkers) {
      boolean isAdmin =
          users.findById(m.refId()).map(AppUser::role).filter(r -> r == Role.ADMIN).isPresent();
      if (isAdmin && markers.countOtherActiveAdmins(system, seededIds) == 0) {
        keptUsers++;
        continue;
      }
      try {
        int n = deleteInTx(() -> markers.deleteUser(system, m.refId()));
        removed += n;
        markers.unmark(system, Kind.USER, m.refId());
      } catch (DataIntegrityViolationException e) {
        keptUsers++;
      }
    }

    // 4. Organisations, one by one, for the same reason.
    int keptOrgs = 0;
    for (Marker m : orgMarkers) {
      try {
        removed += deleteInTx(() -> markers.deleteOrganisation(system, m.refId()));
        markers.unmark(system, Kind.ORGANISATION, m.refId());
      } catch (DataIntegrityViolationException e) {
        keptOrgs++;
      }
    }

    audit.record(
        QaAuditEvent.QA_WIPE,
        null,
        null,
        QaSeeder.CLIENT_IP,
        Map.of("rows", removed, "kind", "wipe"));
    out.println(
        "qa-wipe: removed "
            + removed
            + " rows; kept "
            + keptOrgs
            + " organisations, "
            + keptUsers
            + " accounts and "
            + keptTickets
            + " tickets that the seed did not create"
            + (keptTickets > 0 ? " (use --include-tester-data to remove tester tickets too)" : "")
            + ".");
    return 0;
  }

  private int deleteInTx(java.util.function.IntSupplier work) {
    Integer n = tx.execute(status -> work.getAsInt());
    return n == null ? 0 : n;
  }

  private void deleteBlobs(List<BlobKeys> files) {
    for (BlobKeys f : files) {
      for (UUID key : new UUID[] {f.storageKey(), f.thumbnailKey()}) {
        if (key == null) {
          continue;
        }
        try {
          blobs.delete(key);
        } catch (IOException e) {
          // The row is gone; an orphan file is harmless and the next backup prune ignores it.
        }
      }
    }
  }
}

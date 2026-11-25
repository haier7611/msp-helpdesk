package com.ticketsystem.api.mail.intake.poller;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.mail.intake.imap.ImapMailboxClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Which failures the poller retries next round instead of filing as triage (AUD-13 L4), and where a
 * new mailbox starts (ADR-033).
 */
class ImapPollerTest {

  @Test
  void transientDatabaseErrorsAreRetriedOthersAreNot() {
    assertThat(ImapPoller.isTransient(new CannotAcquireLockException("deadlock"))).isTrue();
    assertThat(ImapPoller.isTransient(new RuntimeException(new QueryTimeoutException("slow"))))
        .isTrue();
    assertThat(ImapPoller.isTransient(new CannotGetJdbcConnectionException("down"))).isTrue();
    assertThat(ImapPoller.isTransient(new IllegalStateException("bug"))).isFalse();
    assertThat(ImapPoller.isTransient(new DataIntegrityViolationException("bad"))).isFalse();
  }

  @Test
  void theStartingPositionIsUidNextOrTheHighestUidPlusOne() {
    // The server reported UIDNEXT: use it as is.
    assertThat(ImapMailboxClient.startUid(57, 0)).isEqualTo(57);
    // No UIDNEXT (-1 or 0): one past the highest UID in the folder.
    assertThat(ImapMailboxClient.startUid(-1, 41)).isEqualTo(42);
    assertThat(ImapMailboxClient.startUid(0, 41)).isEqualTo(42);
    // An empty folder without UIDNEXT starts at UID 1.
    assertThat(ImapMailboxClient.startUid(-1, 0)).isEqualTo(1);
  }
}

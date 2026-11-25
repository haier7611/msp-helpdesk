package com.ticketsystem.api.ticket.attachment.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes uploads that were never attached to a comment within 24 hours (row and files). Runs every
 * 30 minutes with a SYSTEM scope (it must see every uploader's pending files).
 */
@Component
public class PendingAttachmentCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(PendingAttachmentCleanupJob.class);
  static final int BATCH = 500;

  private final AttachmentService attachments;

  public PendingAttachmentCleanupJob(AttachmentService attachments) {
    this.attachments = attachments;
  }

  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
  public void run() {
    int n = runOnce();
    if (n > 0) {
      log.info("deleted {} expired pending attachments", n);
    }
  }

  /** One batch; returns how many were deleted. */
  public int runOnce() {
    return attachments.deleteExpiredPending(
        AccessScope.system("delete expired pending attachments"), BATCH);
  }
}

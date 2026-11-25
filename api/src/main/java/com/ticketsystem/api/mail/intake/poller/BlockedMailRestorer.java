package com.ticketsystem.api.mail.intake.poller;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.mail.intake.InboundMailProcessor;
import com.ticketsystem.api.mail.intake.job.InboundAttachmentWorker;
import org.springframework.stereotype.Component;

/**
 * Files a restored Blocked mail the way the poller would have filed it: the admin asks for the
 * restore (checked here and in the processor), but the ticket, its contact and the client's files
 * are written with the poller's SYSTEM scope, so the files have no uploader and never read as the
 * admin's uploads. The admin is recorded on the Blocked mail row and in the audit log. It lives
 * with the poller because only the poller files mail with a SYSTEM scope.
 */
@Component
public class BlockedMailRestorer {

  private final InboundMailProcessor processor;
  private final InboundAttachmentWorker attachmentWorker;

  public BlockedMailRestorer(
      InboundMailProcessor processor, InboundAttachmentWorker attachmentWorker) {
    this.processor = processor;
    this.attachmentWorker = attachmentWorker;
  }

  /** Restores the mail; its files are processed after the commit, like polled mail. */
  public InboundMailProcessor.Outcome restore(AccessScope admin, long inboundMailId) {
    admin.requireAdmin();
    InboundMailProcessor.Outcome o =
        processor.restore(admin, AccessScope.system("restore blocked mail"), inboundMailId);
    attachmentWorker.dispatch(o.attachmentJobs(), null);
    return o;
  }
}

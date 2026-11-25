package com.ticketsystem.api.mail.intake.imap;

/**
 * A mailbox could not be reached. {@link #category()} is a short, fixed description that is safe to
 * store and show ("login failed", "token request failed"); the server's own text, which can echo
 * user names, is never kept.
 */
public class MailboxAccessException extends Exception {

  /** What went wrong, as a fixed category. */
  public enum Category {
    CONNECTION_FAILED("connection failed"),
    LOGIN_FAILED("login failed"),
    TOKEN_REQUEST_FAILED("token request failed"),
    FOLDER_NOT_FOUND("folder not found"),
    NOT_CONFIGURED("mailbox not fully configured"),
    SECRET_UNREADABLE("stored secret unreadable"),
    READ_FAILED("reading mail failed");

    private final String label;

    Category(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private final Category category;

  public MailboxAccessException(Category category) {
    super(category.label());
    this.category = category;
  }

  public Category category() {
    return category;
  }
}

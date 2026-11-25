# Connect a Gmail mailbox (ADR-034, ADR-046)

For a support address on Gmail or Google Workspace. Microsoft 365 uses OAuth2 instead (see
[entra-app-registration.md](entra-app-registration.md) for sign-in, and
[../30-reliability.md](../30-reliability.md) "Mailbox prerequisites" for the mailbox app).

## Before you start

1. In the Google account of the support address, turn on 2-Step Verification.
2. Create an **app password** (Google Account - Security - App passwords). Copy the 16 characters;
   Google shows them once. The normal account password does not work for IMAP or SMTP.
3. Gmail plus-addressing (`support+internal@helpdesk.example`) works out of the box.

## Connect Gmail

**Admin - Email and signature - Mailboxes - Add mailbox** (saving asks for your authenticator code):

| Field | Value |
| --- | --- |
| Support address | the Gmail or Workspace address, such as `support@helpdesk.example` |
| IMAP server / port / security | `imap.gmail.com` / `993` / TLS |
| Sign-in method | Password |
| User name | the full address |
| Password | the app password |
| Outgoing email - SMTP server | `smtp.gmail.com` |
| SMTP port / security | `465` / TLS, or `587` / STARTTLS |
| Use the same sign-in as incoming | on |

The trusted authserv-id (under Advanced) can stay empty: for `imap.gmail.com` it is saved as
`mx.google.com`.

Save, then press **Test connection**. It signs in to IMAP and SMTP and sends a test email to
`support+internal@`; press **Check the test email** after a minute. If the SMTP sign-in fails,
create a new app password and enter it again. Then switch the mailbox on; only mail that arrives
after that becomes tickets.

## What changes

- Replies, ticket confirmations, status emails and agent alerts go out through Gmail, from the
  support address (they also appear in Gmail's Sent folder).
- Invitations, password resets, sign-in links and other account emails also go out through Gmail,
  from the support address with the support name (ADR-046). If Gmail refuses the sign-in they wait
  and retry, like ticket emails, until the SMTP settings are fixed; the mailbox shows the last
  sending error.
- Emails to test addresses (`.example`, `.test`, `.invalid`, `.localhost`) are not sent through
  Gmail; the ticket shows "Not emailed: test address".
- To go back to the server's mail settings (`SMTP_*` in `.env`), clear the SMTP server field and
  save.

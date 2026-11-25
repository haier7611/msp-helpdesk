# Admin guide

For the people who set up and look after the helpdesk. Each section is one task. Server tasks
(installing, backups, upgrades) are in [../deployment.md](../deployment.md) and
[../09-operations.md](../09-operations.md).

## Before you start

- You need an admin account with two-step sign-in (an authenticator app). The very first admin is
  created once with the setup token on the server ([../deployment.md](../deployment.md) step 4).
- Sensitive changes ask for your 6-digit code again ("step-up"). One code covers 10 minutes.
- Every change you make is written to the audit log (**Admin - Audit log**).

## 1. Set up your branding

1. Open **Admin - Branding**.
2. Enter the **support name** (shown in emails as "Alex Tran (Your Support Name)"), your company
   address and colours, and upload your logo.
3. Save, then send yourself a test reply from a ticket to see how it looks in your inbox.

## 2. Connect the support mailbox

Email to your support address becomes tickets, and replies go out from it.

1. Create a dedicated mailbox, for example `support@yourcompany.example`, with plus-addressing on
   (`support+internal@` must arrive in the same mailbox).
2. Open **Admin - Email and signature - Mailboxes - Add mailbox** and enter the server details:
   - Microsoft 365: sign-in type **OAuth2** with tenant id, client id and client secret from an
     Entra app that has `IMAP.AccessAsApp` (and `SMTP.SendAsApp` to send through it);
   - Google: sign-in type **Password** with an app password
     ([../runbooks/connect-gmail.md](../runbooks/connect-gmail.md)); other hosts the same way.
3. Check the **trusted authserv-id** under Advanced (Microsoft 365: `(none)`; left empty it is
   filled in for Gmail and Microsoft 365 hosts). A wrong value sends every email to Email triage.
4. Press **Test connection** and wait for "arrived" (up to a minute).
5. Switch the mailbox **on**. Only mail that arrives after that becomes tickets; mail already in the
   mailbox stays out.

Details and troubleshooting: [../30-reliability.md](../30-reliability.md) "Inbound mail".

## 3. Set the company signature template

1. Open **Admin - Email and signature - Company signature**.
2. Lay out the fixed company part (company name, address, logo, colours) around each agent's own
   fields (name, job title, phone, mobile, one free line).
3. Check the preview and save (needs step-up). Agents see their own result in **My settings -
   Profile and signature**.

## 4. Review the request types

1. Open **Admin - Request types**. Eight client types and a hidden "Alert" type (for monitoring
   integrations) come ready-made.
2. Rename, add or remove types to fit your clients, and set each type's **starting priority** and
   optional **default assignee**. Agents can still change the priority on a ticket.

## 5. Set the SLA

1. Open **Admin - SLA policies**.
2. For each organisation, check the business hours, timezone and holidays.
3. Set first-response and resolution targets per priority (and per request type if needed), and
   whether **Pending on vendor** pauses the clock (off by default). P1 runs 24/7.

## 6. Add organisations

1. Open **Admin - Organisations - New organisation**.
2. Enter the name, ticket prefix (for example `ACD` gives `ACD-001`), timezone and the client's
   verified email domains (public mail domains are refused).
3. Add the client's contacts under **Admin - Customers**, or let them be added from Email triage.

## 7. Invite agents

1. Open **Admin - Agents - Invite**.
2. Enter their email and name, choose **Agent** or **Admin**, and which organisations they work (all,
   or chosen ones). Confirm with your code.
3. They get an email with a link that works once, for 48 hours. Opening it and choosing a password
   is enough; there is no code to read out. Anyone with that email can accept, so ask them not to
   forward it, and **Revoke** a pending invitation you no longer need.
4. They set up an authenticator app at first sign-in.

If an agent loses their phone and recovery codes, reset their two-step sign-in in **Admin - Agents**.
For a short, documented exception you can also **pause two-step sign-in** for one agent, with a
reason and an end date.

When someone leaves: **Deactivate** them (their open tickets are moved on), then, if you no longer
need their details, erase their personal data (section 10).

## 8. Turn on "Sign in with Microsoft" (optional)

Off by default. Follow
[../runbooks/entra-app-registration.md](../runbooks/entra-app-registration.md) to create the app in
your Microsoft tenant, then enter the details in **Admin - Sign-in**, run **Test sign-in**, and
switch it on.

## 9. Use the block list

For spam that keeps coming in.

1. On a spam ticket choose **Block sender** or **Block domain** (this also closes the ticket as spam,
   with no email to the sender). Or add rules in **Admin - Block list**.
2. Blocked mail creates no ticket and gets no reply. It is kept for 30 days in the **Blocked mail
   log**, where you can **Restore** a message as a ticket or remove the rule.
3. Blocking a client's domain or a saved contact shows a warning and needs your code.

## 10. Erase a person's personal data

When a client contact or a former agent asks to be forgotten:

- **Client contacts**: **Admin - Customers**, open the person, **Erase personal data**, confirm with
  your code.
- **Former agents**: deactivate them first, then call
  `POST /api/v1/admin/users/{id}/erase` with a step-up code (there is no screen for agents yet).

Their name becomes "Former user" and their email address is removed everywhere; their tickets and
the words they wrote stay. This cannot be undone. Backups forget them within 30 days, and a restore
replays the erasure log so they stay forgotten.

## 11. Keep it healthy

- Watch the bell for SLA alerts and the Email triage queue daily.
- Retention runs every night: closed tickets older than each organisation's retention period
  (default 3 years) are deleted with their attachments.
- Saved replies shared by the whole team live in **Admin - Saved replies**.
- Server tasks (backups, restore drill, key rotation, moving to another address) are in
  [../30-reliability.md](../30-reliability.md) and the runbooks.

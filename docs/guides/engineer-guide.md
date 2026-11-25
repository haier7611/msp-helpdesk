# Engineer guide

For agents (engineers and admins) who work tickets every day.

## Sign in

Use your email and password, then the 6-digit code from your authenticator app. Tick "trust this
device for 30 days" on your own computer to skip the code at sign-in. If you lose your phone, use a
recovery code or ask an admin to reset your two-step sign-in.

## The queue

- **My tickets** opens by default (change it in My settings - Preferences).
- Other views: **Unassigned**, **Pending on client**, **Pending on vendor**, **Email triage**, **All
  open tickets**, one per organisation, and **Breaching** (close to or past the SLA).
- Search by subject, reporter email or any ticket reference (old references work too).
- **Export CSV** downloads exactly what you see (up to 10,000 rows).
- A ticket you assign to yourself, or answer first, moves from New to Open.

## Email triage

Mail that could not be matched safely lands here with the reason (unknown sender, reference not
found, sender not authenticated ...). Open it, choose the right **organisation**, and tick:

- **Save the sender as a contact** - their next email goes straight to that organisation;
- **Add the domain as a verified domain** - everyone from that domain does.

The ticket then gets a new reference from that organisation; the old `TRI-` reference keeps working.

## Working a ticket

The composer at the bottom has two tabs:

- **Internal note** (the default) - only agents see it. Use it for findings, passwords you
  must not email, and questions to colleagues. Notes never reach the client.
- **Reply to client** - emailed to the reporter and confirmed participants, with your signature
  (full the first time, short after that).

Before you send, check the tab. The send button offers **Send to client** or **Send and set
Pending on client**.

Paste or drop screenshots straight into the text; images are cleaned of location data. Files up to
20 MB each.

## Saved replies

Type `/` in the composer to pick a saved reply. Placeholders such as `{client_first_name}` and
`{ticket_ref}` are filled in for you. Keep your own in My settings - My saved replies; shared ones
are managed by admins in Admin - Saved replies.

## Statuses

| Status | Use it when | What happens |
| --- | --- | --- |
| New | Nobody has picked it up yet | SLA clock running |
| Open | You are working on it | SLA clock running |
| Pending on client | You need something from the client | SLA paused; the client gets reminders and the ticket resolves itself if they never answer; their reply moves it back to Open |
| Pending on vendor | You are waiting on a supplier (Microsoft, an ISP ...) | No email to the client; SLA keeps running unless the policy says it pauses |
| Resolved | The problem is fixed | The client is told; it closes itself after 5 days unless they reply |
| Closed | Finished | A client reply opens a new linked follow-up ticket; you can still add internal notes |

## Duplicates and links

- **Mark as duplicate** (same organisation only) closes the ticket and points its references at the
  original, so later emails land on the right one.
- **Link** related tickets of the same organisation.

## Replying by email

If you are away from the app you can reply to the helpdesk's emails:

- A reply to an **internal notification** (from `support+internal@`) is always saved as an internal
  note - never sent to the client.
- A reply to a **client email thread** becomes a public reply only if your admin has switched on
  "Notes and public replies" (Admin - Email and signature - Agent email replies); otherwise it
  goes to Email triage. Keep the `[REF]` in the subject.

## Your settings

**My settings**: profile and signature fields, photo, password and two-step sign-in, trusted
devices, active sessions, notifications (email or bell or both, daily digest), preferences, out of
office (you are skipped by auto-assignment while away) and your own saved replies.

See also: [client-guide.md](client-guide.md) for what your clients see, and
[admin-guide.md](admin-guide.md) for the settings admins control.

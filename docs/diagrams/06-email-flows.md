# 06 - Email flows

What this shows: how an inbound mail becomes a ticket or a comment (the decision order, split into two diagrams), and how an outbound mail is built, queued, sent and retried. The full inbound flowchart as first drawn is in [docs/15-product-spec.md](../15-product-spec.md) section 6; this page restates it in two smaller parts with the guards spelled out and does not replace it.

Reflects: [ADR-005](../10-decision-log.md) IMAP polling, [ADR-011](../10-decision-log.md) signatures, [ADR-014](../10-decision-log.md) references and threading, [ADR-020](../10-decision-log.md) mailbox config, [ADR-022](../10-decision-log.md) matching to organisations. Spec: [section 5](../15-product-spec.md) signatures, [section 6](../15-product-spec.md), [section 11b](../15-product-spec.md). Ops: [docs/30-reliability.md](../30-reliability.md) (outbox and inbound runbooks).

## 1. Inbound: first checks and ticket matching

```mermaid
flowchart TD
  P["ImapPoller reads a message every 30 s<br/>one at a time, over 30 MB: only 256 KB kept"] --> D{"Message-ID already seen?"}
  D -->|"yes"| DUP["Log DUPLICATE, change nothing"]
  D -->|"no"| B{"Sender or domain on the block list?"}
  B -->|"yes"| BL["Blocked mail log, 30 days<br/>no ticket, no reply"]
  B -->|"no"| R{"Reference in the subject, or<br/>In-Reply-To / References matches our outbox?"}
  R -->|"no"| NEW["Go to part 2: new mail"]
  R -->|"yes"| G{"Sender is reporter, participant or agent<br/>with access AND DMARC pass?"}
  G -->|"no"| TRI["New triage ticket TRI-nnn<br/>reason shown, matched ticket untouched"]
  G -->|"yes"| A{"Automated or auto-reply?"}
  A -->|"yes"| H["Hidden system entry<br/>no reopen, no auto-response"]
  A -->|"no"| W{"Sent to support+internal<br/>or reply to an internal notification?"}
  W -->|"yes, agent"| NOTE["Internal note"]
  W -->|"no"| CR["Client reply on the ticket<br/>Pending moves to Open; Closed opens a follow-up"]
```

## 2. Inbound: new mail with no ticket match

```mermaid
flowchart TD
  S["New mail, no ticket matched"] --> CAP{"More than 20 new mails this hour<br/>from this sender, same authentication class?"}
  CAP -->|"yes"| FOLD["Added as a comment to that sender's latest ticket<br/>assignee notified"]
  CAP -->|"no"| SR{"Sender rule matches AND DMARC pass?"}
  SR -->|"yes"| T1["New ticket, rule's organisation and request type"]
  SR -->|"no"| KC{"Known contact AND DMARC pass?"}
  KC -->|"yes"| T2["New ticket for their organisation"]
  KC -->|"no"| VD{"Verified domain of an organisation AND DMARC pass?"}
  VD -->|"yes"| T3["New ticket, contact created unverified"]
  VD -->|"no"| TR["Email triage ticket TRI-nnn with reason"]
```

Every mail ends as a ticket, a comment, a hidden system entry, or (block list only) a log row. Triage tickets get no automatic emails (the sender may be forged). Agents can later assign a triage ticket to an organisation, optionally saving the contact or domain; the ticket gets a new primary reference and keeps `TRI-nnn` as an alias.

## 3. Outbound: building and sending

```mermaid
flowchart TD
  E["Business event in the api<br/>public reply, confirmation, resolved, closed,<br/>reminder, internal alert, security notice"] --> RC["Recipients checked at send time<br/>reporter and confirmed participants only,<br/>never automated addresses"]
  RC --> BU["Build message<br/>subject with [REF], footer, signature,<br/>Reply-To, Auto-Submitted, In-Reply-To / References"]
  BU --> Q["INSERT outbox_message in the caller's transaction<br/>own Message-ID stored"]
  Q --> NU["After commit: nudge the outbox-worker"]
  NU --> CL{"Claim row, SKIP LOCKED"}
  CL --> SM["Send by SMTP"]
  SM -->|"ok"| SENT["status sent"]
  SM -->|"4xx or connection error"| RT["Retry after 1, 2, 4, 8, 16, 32 min, then failed"]
  SM -->|"5xx permanent"| FA["status failed at once"]
  CL -.->|"stuck in sending over 10 min"| BK["Put back in the queue<br/>at-least-once, may send twice"]
```

- **Signature** (ADR-011, spec section 5): public replies carry the agent's name as display name, the company signature template and fixed profile fields. The rendering rules are in spec section 5.
- **Reply-To** is the support mailbox for client mail and `support+internal@` for agent-only alerts; plus-addressing must work on the mailbox (the admin "test connection" checks it).
- **Local development** (`dev` profile): SMTP goes to Mailpit, nothing leaves the host. With an empty `SMTP_HOST` mail is queued but not sent.
- **Sensitive mail** (invitation, reset, magic link): bodies are blanked once sent or failed and it is never sent after `expires_at`; a failed one cannot be resent, the user asks again.
- Stuck mail: runbook "mail stuck in the outbox" in [docs/30-reliability.md](../30-reliability.md).

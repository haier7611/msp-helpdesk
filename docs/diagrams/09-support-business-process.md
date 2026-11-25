# 09 - Support business process

What this shows: the end-to-end MSP support process, for a business reader. Who does what from the moment a client asks for help until the ticket is closed, with the time targets from the SLA. Lanes are roles; the system does the automatic steps.

Reflects: [ADR-003](../10-decision-log.md) intake channels, [ADR-017](../10-decision-log.md) SLA targets, [ADR-018](../10-decision-log.md) raise-only portal, [ADR-022](../10-decision-log.md) every email becomes a ticket. Spec: [section 3](../15-product-spec.md), [section 4](../15-product-spec.md), [section 4.7](../15-product-spec.md). Lifecycle detail: [05-ticket-lifecycle.md](05-ticket-lifecycle.md).

## 1. Intake and triage

```mermaid
flowchart LR
  subgraph Client
    C1["Raises a ticket in the portal"]
    C2["Emails the support address"]
    C3["Phones or walks in"]
  end
  subgraph Monitoring
    M1["Alert tool posts via API key"]
  end
  subgraph System
    S1["Ticket created, reference issued,<br/>SLA started, default assignee applied"]
    S2["Unmatched email:<br/>Email triage queue with reason"]
    S3["Confirmation email to the client"]
  end
  subgraph Agent
    A1["Agent creates the ticket<br/>on the client's behalf"]
    A2["Assigns triage ticket to an organisation<br/>optionally remembers sender or domain"]
    A3["Picks up: status Open"]
  end
  C1 --> S1
  C2 -->|"matched sender"| S1
  C2 -->|"unknown or unauthenticated"| S2
  C3 --> A1 --> S1
  M1 --> S1
  S2 --> A2 --> S1
  S1 --> S3
  S1 --> A3
```

## 2. Work, wait and close

```mermaid
flowchart TD
  W["Agent works the ticket<br/>public replies to the client, internal notes<br/>priority, request type, reassign"] --> Q{"What is needed?"}
  Q -->|"answer from the client"| PC["Pending on client<br/>SLA clock paused"]
  Q -->|"third party: supplier, ISP, Microsoft"| PV["Pending on vendor<br/>no client emails"]
  Q -->|"fixed"| RES["Resolved<br/>client emailed, SLA stops"]
  PC -->|"client replies by email"| W
  PC -->|"3 business days"| REM["System sends one reminder"]
  PC -->|"7 business days, no reply"| RES
  PV -->|"agent moves it on"| W
  RES -->|"client replies or agent reopens"| W
  RES -->|"5 days, no reply"| CL["Closed<br/>client emailed, read only"]
  CL -->|"client replies later"| FU["Follow-up ticket, linked"]
  FU --> W
```

## Who does what

| Step | Client | Agent | Admin | System |
| --- | --- | --- | --- | --- |
| Raise | Portal form or email | Create for a client | - | Reference, SLA, confirmation |
| Triage | - | Assign unmatched mail, mark duplicates | Block list, sender rules | Show the reason for triage |
| Work | Answer questions by email | Reply, note, status, priority | - | Notify assignee, flag 80% and breach |
| Wait | Reply when ready | Choose Pending on client or vendor | Set timers per organisation | Reminder, auto-resolve |
| Resolve and close | Reply to reopen | Resolve, close, duplicate | - | Auto-close, Closed notice |
| Report | - | SLA report, CSV for monthly client reports | Audit log | Met or breached stored per ticket |

Time targets (defaults, admin can change per organisation): P1 first response 30 min and resolution 4 h, 24/7; P2 1 h and 8 business hours; P3 4 business hours and 3 business days; P4 1 business day and 10 business days (spec 4.7). Clients never see SLA information.

# Demo walkthrough

A scripted tour of MSP Helpdesk on your own machine, using only the synthetic demo data. It suits a
screen recording or a live demo and takes about 20 minutes. Every name, address and ticket in it is
made up and lives on reserved `.example` domains.

## Set up

1. Start the stack with the demo data
   ([local-development.md](local-development.md)):

   ```sh
   scripts/dev-up.sh --demo
   ```

2. Open `demo-credentials.txt` in the repository root (mode 600). One line per account:
   `account|email|password|role|organisation|recovery codes`. Keep this file off screen.
3. Open Mailpit at `http://localhost:8025/mailpit/`. It is the inbox of every demo person. It also
   holds sign-in links, so show only the messages named below.

### The demo data

| Organisation | Prefix | People (client logins) |
| --- | --- | --- |
| Acme Dental | `ACD` | Dana Reyes, Sam Okoye |
| Northwind Logistics | `NWL` | Priya Nair, Tomas Berg |
| Bluefin Architects | `BFA` | Lena Ito |
| QA Testers | `QA` | five test accounts (ignore during the demo) |

| Staff login | Role | Works |
| --- | --- | --- |
| `qa-admin@msp.example` | Admin | everything |
| `alex@msp.example` | Agent | all organisations |
| `riley@msp.example` | Agent | Bluefin Architects only |

Ten tickets are already there in different states: for example a printer fault at Acme Dental
(open, Alex), an Outlook sign-in problem waiting on the client (with an internal note and image),
Wi-Fi drops at Northwind waiting on a vendor, and a closed workstation replacement at Bluefin.

### Browser tips

- Use **two browser windows**: a normal one for the staff login and a private one for the client
  login. One browser profile can only be signed in as one person at a time.
- Staff sign-in asks for a two-step code. Type one of the account's recovery codes (each works once)
  and tick **Trust this device for 30 days** so it does not ask again in that browser.
- After the first staff sign-in, open **My settings - Profile and signature** and set a name, job
  title and phone; they appear in the email signature later.

## The story

1. **A client raises a ticket** (private window, Dana Reyes of Acme Dental). **New ticket** - pick a
   request type (each has a short description) - subject and description - how many people it
   affects and how badly - attach a screenshot - **Send**. The success page shows the reference (for
   example `ACD-005`) and says the reply comes by email. In Mailpit, show the confirmation email with
   `[ACD-005]` in the subject.
2. **An agent picks it up** (normal window, Alex). **Queue - Unassigned**, open the ticket. Point out
   the SLA badge (first response due). **Assign to me**; the ticket moves from New to Open and the
   timeline records the assignment.
3. **Internal note** (Alex). Composer tab **Internal note** - paste an image - save. It is marked
   internal; clients never see it and it is never emailed.
4. **Reply to the client** (Alex). Composer tab **Reply to client** - write a reply - **Send and set
   Pending on client**. The SLA clock pauses. In Mailpit, show the email: Alex's name and the full
   signature with the logo.
5. **The client answers by email.** Explain that Dana can reply to that email and the reply lands on
   the same ticket because of `[ACD-005]` in the subject. (Email intake needs a connected mailbox;
   the local stack has none, so describe it or show the portal instead.)
6. **Client separation** (Riley, Bluefin only). Sign in as Riley in the normal window. Paste the link
   of Alex's Acme Dental ticket: the app answers that it cannot find it. Riley's queue shows Bluefin
   tickets only. Every query is limited to the organisations an agent may see, on the server.
7. **Admin tour** (`qa-admin@msp.example`). **Admin - Request types** (eight general IT types; Alert
   is reserved for monitoring integrations), **SLA policies** (P1 runs 24/7), **Email and
   signature** (company signature and preview), **Block list**, **Audit log** (every admin action,
   with who and when). **Agents**: invite, roles, and pausing two-step sign-in for one agent with a
   reason and an end date.
8. **Finish.** In the queue, filter and **Export CSV**. Account menu - **Sign out**, which really ends
   the session.

## Skip or explain

- **Email to ticket** needs a connected mailbox; see [admin-guide.md](admin-guide.md) section 2.
- **Sign in with Microsoft** needs your own Entra app registration; it is off in the demo.
- The **QA Testers** organisation is test data; leave it out of the recording.

## Afterwards

- New passwords for every demo account: `scripts/dev-seed.sh --reset-passwords`.
- Remove everything, demo data included: `scripts/dev-down.sh --wipe` (asks you to type the project
  name).

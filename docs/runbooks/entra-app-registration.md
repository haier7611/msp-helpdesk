# Runbook - create the Microsoft Entra app registration for "Sign in with Microsoft"

For: any admin of the MSP's own Microsoft 365 tenant. Time: about 15 minutes. Background: spec
sections 7 and 11c, ADR-025. You need the **Application Administrator** or **Cloud Application
Administrator** role in Entra (Global Administrator also works).

What you end with: four values to type into the helpdesk at **Admin - Sign-in**: the application
(client) ID, a client secret, the date that secret expires, and your own tenant ID. Nothing goes
into server files or `.env`; the helpdesk keeps the secret encrypted in its database and never shows
it again.

Microsoft sign-in stays **off** until you run **Test sign-in** and then switch it on. Passwords keep
working the whole time.

## 1. Find the redirect URI

1. Sign in to the helpdesk as an admin and open **Admin - Sign-in**.
2. Copy the **Redirect URI** shown at the top of "App registration". It is always the helpdesk's
   public address (`TS_PUBLIC_BASE_URL`) followed by `/api/v1/auth/microsoft/callback`, for example:

   `https://helpdesk.example/api/v1/auth/microsoft/callback`

   If the helpdesk moves to another address, add the new redirect URI in Entra too (step 2.5), or
   Microsoft sign-in stops working at the new address
   ([move-domain-or-server.md](move-domain-or-server.md)).

## 2. Create the app registration

1. Go to <https://entra.microsoft.com> and sign in with your admin account.
2. Open **Identity - Applications - App registrations** and choose **New registration**.
3. **Name**: something people will recognise on the Microsoft consent screen, for example
   `Example MSP Helpdesk`. Customers from other organisations see this name.
4. **Supported account types**: choose **Accounts in any organizational directory (Any Microsoft
   Entra ID tenant - Multitenant)**.
   - Not "personal Microsoft accounts": the helpdesk refuses personal accounts anyway.
   - Multitenant is needed because customers sign in from their own organisation's tenant. The
     helpdesk itself decides which tenants are allowed (your tenant for agents, one tenant per
     customer organisation), so multitenant does not let strangers in.
5. **Redirect URI**: platform **Web**, and paste the redirect URI from step 1. It must match
   exactly: `https`, no trailing slash.
6. Choose **Register**.
7. On the **Overview** page, copy:
   - **Application (client) ID**, which you will type into the helpdesk;
   - **Directory (tenant) ID**, which is your **MSP tenant ID**.

## 3. Authentication settings

1. Open **Authentication**.
2. Under **Implicit grant and hybrid flows**, leave **both boxes unticked** (the helpdesk uses the
   authorisation code flow with PKCE; it never needs tokens in the browser).
3. Leave **Allow public client flows** at **No**.
4. Choose **Save** if you changed anything.

## 4. Token claims

The helpdesk links people by the token's tenant ID (`tid`) and object ID (`oid`), never by email.
`tid` and `oid` are in every ID token when the app asks for the `openid profile` scopes (it does).
Add these optional claims so the other rules work:

1. Open **Token configuration** and choose **Add optional claim**.
2. Token type **ID**. Tick:
   - **amr**: how the person signed in. When it contains `mfa` and the person is in **your own
     tenant**, an agent skips the helpdesk's 6-digit code at sign-in. Without this claim agents are
     always asked for the code (safe, just one more step). A claim from any other tenant is never
     trusted.
   - **acct**: member (0) or guest (1). The helpdesk refuses guest accounts for agents and for the
     customer automatic link. Without this claim every account is treated as a guest, so agents
     cannot use Microsoft sign-in at all. Tick it.
   - **email**: used only to offer the automatic link to a customer (below), never to find an
     account.
   - **xms_edov**: Microsoft's "the email's domain is verified by that tenant". A customer is linked
     automatically only when this is true.
3. Choose **Add**. If Entra asks to add the Microsoft Graph `email` permission, tick it and choose
   **Add**.

## 5. API permissions

1. Open **API permissions**. You should see **Microsoft Graph - User.Read** (delegated), and `email`
   if step 4 added it. Nothing else is needed. Do not add application permissions.
2. Optional: choose **Grant admin consent for <your organisation>** so agents are not asked to
   consent one by one. Each customer organisation's own admin can grant consent for their tenant the
   first time one of their people signs in, or each person consents for themselves if their tenant
   allows it.

## 6. Client secret

1. Open **Certificates & secrets - Client secrets - New client secret**.
2. **Description**: for example `helpdesk <year-month>`. **Expires**: 12 months is a sensible choice
   (Microsoft allows at most 24 months).
3. Choose **Add**, then **copy the Value straight away**; Microsoft shows it only once. Copy the
   **Value**, not the **Secret ID**.
4. Note the **Expires** date shown next to it.
5. Do not paste the secret into email, chat, tickets or a file in a repository. Type or paste it
   directly into the helpdesk (next step).

## 7. Enter the details in the helpdesk

1. In the helpdesk open **Admin - Sign-in**.
2. Fill in **Application (client) ID**, **Client secret** (the Value), **Client secret expires on**
   (the date from step 6.4) and **MSP tenant ID** (the Directory (tenant) ID from step 2.7).
3. Choose **Save details**. The helpdesk asks for a code from your authenticator app (step-up).
   After saving, the secret field is empty again; that is expected, the secret is stored encrypted
   and is never shown.
4. Choose **Test sign-in** and sign in with **an account in your own tenant**. You come back to the
   same page with "Test sign-in passed", or a message with a Microsoft error code (common ones
   below).
5. Choose **Switch on** (step-up again). The sign-in page now shows **Sign in with Microsoft**.

## 8. Let people link their accounts

- **Agents** link from **My settings - Security - Microsoft account - Link Microsoft account**, with
  a fresh authenticator code, using an account in your tenant. Agents are never linked
  automatically.
- **Customers**: for each client organisation that wants it, an admin enters that organisation's
  **tenant ID** in **Admin - Sign-in - Customer organisations** (step-up). The customer's own IT can
  find it in their Entra overview. A customer is then linked automatically at their first "Sign in
  with Microsoft" only when all of these hold: the account is in that tenant, it is not a guest,
  Microsoft says the email domain is verified (`xms_edov`), the email equals the customer's email in
  the helpdesk, and that domain is one of the organisation's verified domains. Otherwise they sign in
  with their password or an email link first and link the account themselves.

## 9. Renew the secret before it expires

The helpdesk shows admins a banner in Admin from 30 days before the date you entered. To renew:

1. In Entra, **Certificates & secrets - New client secret** (keep the old one for now).
2. In the helpdesk, **Admin - Sign-in**: paste the new Value and its new expiry date, **Save
   details**. Microsoft sign-in stays on.
3. Choose **Test sign-in** to confirm the new secret works.
4. In Entra, delete the old secret.

Changing the **application ID** or the **MSP tenant ID** switches Microsoft sign-in off until a new
Test sign-in passes.

## Common Test sign-in errors

| Shown | Meaning | Fix |
| --- | --- | --- |
| `AADSTS7000215` | Wrong client secret (often the Secret ID was copied instead of the Value) | Create a new secret, paste its Value |
| `AADSTS7000222` | The client secret has expired | Renew it (section 9) |
| `AADSTS50011` (on Microsoft's page) | The redirect URI does not match | Copy it again from Admin - Sign-in into Authentication - Web |
| `AADSTS700016` (on Microsoft's page) | Wrong application ID, or the app is not multitenant | Check the ID; set supported account types to Multitenant |
| "the account you used is not in the MSP tenant" | You tested with a customer or guest account | Test with an account from your own tenant, or fix the MSP tenant ID |
| "Microsoft could not be reached" | Network or Microsoft outage | Try again later; passwords keep working |

## Switching it off

**Admin - Sign-in - Switch off** (step-up). Everyone signs in with passwords; linked accounts are
kept and work again when it is switched back on. To remove the app entirely, also delete the app
registration in Entra.

import { useEffect } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router";
import type { Role } from "./api/auth";
import { setUnauthorisedHandler } from "./api/client";
import { PlaceholderPage } from "./components/ui";
import { AdminLayout, AgentLayout, PortalLayout, SettingsLayout } from "./layouts/Layouts";
import { AuditLogPage } from "./pages/admin/AuditLogPage";
import { BlockListPage } from "./pages/admin/BlockListPage";
import { BrandingPage } from "./pages/admin/BrandingPage";
import { CustomersAdminPage } from "./pages/admin/CustomersAdminPage";
import { EmailPage } from "./pages/admin/EmailPage";
import { MicrosoftSignInPage } from "./pages/admin/MicrosoftSignInPage";
import { OrganisationsPage } from "./pages/admin/OrganisationsPage";
import { RequestTypesPage } from "./pages/admin/RequestTypesPage";
import { SavedRepliesPage } from "./pages/admin/SavedRepliesPage";
import { SlaAutomationPage } from "./pages/admin/SlaAutomationPage";
import { SlaPoliciesPage } from "./pages/admin/SlaPoliciesPage";
import { SupportersPage } from "./pages/admin/SupportersPage";
import { AgentNewTicketPage } from "./pages/agent/AgentNewTicketPage";
import { AgentOrganisationsPage } from "./pages/agent/AgentOrganisationsPage";
import { CustomersPage } from "./pages/agent/CustomersPage";
import { QueuePage } from "./pages/agent/QueuePage";
import { ReportsPage } from "./pages/agent/ReportsPage";
import {
  SettingsNotificationsPage,
  SettingsOutOfOfficePage,
  SettingsPreferencesPage,
} from "./pages/agent/SettingsPreferencesPages";
import { SettingsProfilePage } from "./pages/agent/SettingsProfilePage";
import { SettingsSecurityPage } from "./pages/agent/SettingsSecurityPage";
import { TicketPage } from "./pages/agent/TicketPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { AccountPage } from "./pages/portal/AccountPage";
import { NewTicketPage } from "./pages/portal/NewTicketPage";
import { AcceptInvitationPage, MagicLinkPage, SetupPage } from "./pages/auth/LinkPages";
import { MfaEnrolPage, MfaVerifyPage } from "./pages/auth/MfaPages";
import { ForgotPasswordPage, ResetPasswordPage } from "./pages/auth/PasswordPages";
import { SignInPage } from "./pages/SignInPage";
import { RequireRole, RoleLanding } from "./RoleGuard";
import {
  ADMIN_PLACEHOLDERS,
  AGENT_PLACEHOLDERS,
  PORTAL_PLACEHOLDERS,
  SETTINGS_PLACEHOLDERS,
} from "./navigation";

// Which roles may open each area (UX only - the server enforces the same gates).
const SUPPORTERS: readonly Role[] = ["AGENT", "ADMIN"];
const ADMINS: readonly Role[] = ["ADMIN"];
const CUSTOMERS: readonly Role[] = ["CUSTOMER"];

/** Sends the user to /sign-in whenever an API call answers 401 (see api/client.ts). */
function UnauthorisedRedirect() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(
    () =>
      setUnauthorisedHandler(() => {
        if (location.pathname !== "/sign-in") {
          navigate("/sign-in", { replace: true });
        }
      }),
    [navigate, location.pathname],
  );
  return null;
}

export function AppRoutes() {
  return (
    <>
      <UnauthorisedRedirect />
      <Routes>
        <Route index element={<RoleLanding />} />
        <Route path="sign-in" element={<SignInPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="magic-link" element={<MagicLinkPage />} />
        <Route path="accept-invitation" element={<AcceptInvitationPage />} />
        <Route path="setup" element={<SetupPage />} />
        <Route path="mfa/verify" element={<MfaVerifyPage />} />
        <Route path="mfa/enrol" element={<MfaEnrolPage />} />

        <Route
          path="agent"
          element={
            <RequireRole allow={SUPPORTERS}>
              <AgentLayout />
            </RequireRole>
          }
        >
          <Route index element={<Navigate to="queue" replace />} />
          <Route path="queue" element={<QueuePage />} />
          <Route path="tickets/new" element={<AgentNewTicketPage />} />
          <Route path="tickets/:ref" element={<TicketPage />} />
          <Route path="customers" element={<CustomersPage />} />
          <Route path="organisations" element={<AgentOrganisationsPage />} />
          <Route path="reports" element={<ReportsPage />} />
          {AGENT_PLACEHOLDERS.map((p) => (
            <Route key={p.path} path={p.path} element={<PlaceholderPage title={p.title} />} />
          ))}
          <Route path="settings" element={<SettingsLayout />}>
            <Route index element={<Navigate to="profile" replace />} />
            <Route path="profile" element={<SettingsProfilePage />} />
            <Route path="security" element={<SettingsSecurityPage />} />
            <Route path="notifications" element={<SettingsNotificationsPage />} />
            <Route path="preferences" element={<SettingsPreferencesPage />} />
            <Route path="out-of-office" element={<SettingsOutOfOfficePage />} />
            {SETTINGS_PLACEHOLDERS.map((p) => (
              <Route key={p.path} path={p.path} element={<PlaceholderPage title={p.title} />} />
            ))}
          </Route>
        </Route>

        <Route
          path="admin"
          element={
            <RequireRole allow={ADMINS}>
              <AdminLayout />
            </RequireRole>
          }
        >
          <Route index element={<Navigate to="supporters" replace />} />
          <Route path="supporters" element={<SupportersPage />} />
          <Route path="organisations" element={<OrganisationsPage />} />
          <Route path="customers" element={<CustomersAdminPage />} />
          <Route path="sign-in" element={<MicrosoftSignInPage />} />
          <Route path="request-types" element={<RequestTypesPage />} />
          <Route path="sla" element={<SlaPoliciesPage />} />
          <Route path="sla-automation" element={<SlaAutomationPage />} />
          <Route path="email" element={<EmailPage />} />
          <Route path="block-list" element={<BlockListPage />} />
          <Route path="branding" element={<BrandingPage />} />
          <Route path="audit-log" element={<AuditLogPage />} />
          <Route path="saved-replies" element={<SavedRepliesPage />} />
          {ADMIN_PLACEHOLDERS.map((p) => (
            <Route key={p.path} path={p.path} element={<PlaceholderPage title={p.title} />} />
          ))}
        </Route>

        <Route
          path="portal"
          element={
            <RequireRole allow={CUSTOMERS}>
              <PortalLayout />
            </RequireRole>
          }
        >
          <Route index element={<Navigate to="new-ticket" replace />} />
          <Route path="new-ticket" element={<NewTicketPage />} />
          <Route path="account" element={<AccountPage />} />
          {PORTAL_PLACEHOLDERS.map((p) => (
            <Route key={p.path} path={p.path} element={<PlaceholderPage title={p.title} />} />
          ))}
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </>
  );
}

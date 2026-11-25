import { describe, expect, it } from "vitest";
import { auditEventLabel } from "./auditLabels";

describe("auditEventLabel", () => {
  it.each([
    ["SIGN_IN_FAILED", "Sign in failed"],
    ["MFA_RESET", "MFA reset"],
    ["API_KEY_CREATED", "API key created"],
    ["SLA_SETTINGS_CHANGED", "SLA settings changed"],
    ["QA_SEED", "QA seed"],
    ["SUPPORTER_ROLE_CHANGED", "Agent role changed"],
    ["MFA_SIGN_IN_SUCCEEDED", "MFA sign in succeeded"],
  ])("%s reads %s", (type, label) => {
    expect(auditEventLabel(type)).toBe(label);
  });
});

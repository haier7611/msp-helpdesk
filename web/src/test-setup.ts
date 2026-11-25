import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// jsdom has no canvas; axe-core probes it for icon fonts and jsdom logs "Not implemented" noise.
// Returning null is what a browser without canvas support does.
HTMLCanvasElement.prototype.getContext = (() =>
  null) as typeof HTMLCanvasElement.prototype.getContext;

// findBy*/waitFor poll for 1 s by default. The route tree resolves lazy chunks and mocked fetches,
// which took longer than that when the CI host ran two builds at once (routes.test.tsx flaked). A
// passing query returns as soon as it matches, so the larger ceiling only costs time on a failure.
configure({ asyncUtilTimeout: 5000 });

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

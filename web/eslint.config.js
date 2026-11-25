import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "coverage", "node_modules"] },
  {
    files: ["**/*.{ts,tsx,js}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
    },
  },
  {
    // Production code never shows demo data: no value imports from src/mocks (types are fine, they
    // carry no data). Tests and the mocks themselves are exempt.
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/**/*.test.{ts,tsx}", "src/test/**", "src/mocks/**", "src/test-setup.ts"],
    rules: {
      "@typescript-eslint/no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["**/mocks/*", "**/mocks"],
              allowTypeImports: true,
              message:
                "Production code must not import demo data from mocks/. Use the API, or allow-list the file in eslint.config.js with a reason.",
            },
          ],
        },
      ],
    },
  },
);

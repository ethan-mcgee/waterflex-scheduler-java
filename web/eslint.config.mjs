// Flat config (ESLint 9+; ESLint 10 no longer reads .eslintrc). Same rules as the former .eslintrc.json: Next's
// core-web-vitals preset, the typescript-eslint parser with the project's tsconfig, and the unsafe-any family as errors.
// The parser and plugin come from the typescript-eslint package, the one eslint-config-next registers, so both configs
// share one plugin object (ESLint refuses two different objects under the "@typescript-eslint" name).
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import tseslint from "typescript-eslint";

const config = [
  { ignores: [".next/**", "node_modules/**", "public/**", "playwright-report/**", "test-results/**", "next-env.d.ts"] },
  ...nextCoreWebVitals,
  {
    files: ["**/*.ts", "**/*.tsx"],
    languageOptions: { parser: tseslint.parser, parserOptions: { project: "./tsconfig.json", tsconfigRootDir: import.meta.dirname } },
    plugins: { "@typescript-eslint": tseslint.plugin },
    rules: {
      "@typescript-eslint/no-unsafe-assignment": "error",
      "@typescript-eslint/no-unsafe-call": "error",
      "@typescript-eslint/no-unsafe-member-access": "error",
      "@typescript-eslint/no-unsafe-return": "error",
      "@typescript-eslint/no-unsafe-argument": "error",
      "@typescript-eslint/no-unsafe-type-assertion": "error",
      "@typescript-eslint/no-non-null-assertion": "error",
    },
  },
];

export default config;

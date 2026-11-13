import { readFileSync } from "node:fs";

/** The usage lines every tool prints for its token. */
export const TOKEN_USAGE = "(--token-file <file> | --token-file - | --token-env <variable>)";

const VARIABLE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * The bearer token from exactly one source: a file, standard input ("--token-file -"), or an environment variable
 * ("--token-env"). The last two keep a token out of files: a study script issues it into its own environment, and
 * every tool it starts inherits that environment. Throws an Error that names the option at fault.
 */
export function readToken(
  file: string | undefined,
  variable: string | undefined,
  environment: NodeJS.ProcessEnv = process.env,
  read: (source: string | number) => string = source => readFileSync(source, "utf8"),
): string {
  if ((file === undefined) === (variable === undefined)) throw new Error("Give exactly one of --token-file and --token-env");
  let token: string;
  if (variable !== undefined) {
    if (!VARIABLE.test(variable)) throw new Error("--token-env takes an environment variable's name");
    token = (environment[variable] ?? "").trim();
    if (token === "") throw new Error(`The environment variable ${variable} named by --token-env is unset or empty`);
    return token;
  }
  if (file === undefined) throw new Error("Give exactly one of --token-file and --token-env");
  try {
    token = read(file === "-" ? 0 : file).trim();
  } catch (error) {
    throw new Error(`Cannot read --token-file: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (token === "") throw new Error("--token-file holds no token");
  return token;
}

import { PrismaClient, type Prisma } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const MISSING_URL = "DATABASE_URL is not set. The portal database has no default; set it to the portal's PostgreSQL URL.";
const SCHEMA_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** How the pg driver reaches the portal database, from a Prisma-style URL. */
export type PortalConnection = { connectionString: string; schema: string | null };

/**
 * Reads a DATABASE_URL. Prisma 5 honoured a `?schema=` parameter; the pg driver does not, so it is taken out of the URL
 * and applied by the adapter (for generated queries) and as the connection's search_path (for raw SQL).
 */
export function portalConnection(url: string): PortalConnection {
  const parsed = new URL(url);
  if (parsed.protocol !== "postgresql:" && parsed.protocol !== "postgres:") throw new Error("DATABASE_URL must be a postgresql:// URL.");
  const schema = parsed.searchParams.get("schema");
  if (schema !== null && !SCHEMA_NAME.test(schema)) throw new Error(`DATABASE_URL names an invalid schema: ${schema}`);
  parsed.searchParams.delete("schema");
  return { connectionString: parsed.toString(), schema };
}

/** The pg adapter, which connects only when the client first queries, so a build without a database still loads modules. */
class PortalPg extends PrismaPg {
  constructor(private readonly connection: PortalConnection | null) {
    super(connection === null ? {} : {
      connectionString: connection.connectionString,
      ...(connection.schema === null ? {} : { options: `-c search_path=${connection.schema}` }),
    }, connection?.schema == null ? undefined : { schema: connection.schema });
  }
  override connect(): ReturnType<PrismaPg["connect"]> {
    if (this.connection === null) return Promise.reject(new Error(MISSING_URL));
    return super.connect();
  }
  override connectToShadowDb(): ReturnType<PrismaPg["connectToShadowDb"]> {
    if (this.connection === null) return Promise.reject(new Error(MISSING_URL));
    return super.connectToShadowDb();
  }
}

/** A Prisma client on the portal database named by DATABASE_URL (Prisma 7 connects through a driver adapter). */
export function createPrismaClient(options: Omit<Prisma.PrismaClientOptions, "adapter" | "accelerateUrl"> = {}): PrismaClient {
  const url = process.env.DATABASE_URL;
  const connection = url === undefined || url === "" ? null : portalConnection(url);
  return new PrismaClient({ ...options, adapter: new PortalPg(connection) });
}

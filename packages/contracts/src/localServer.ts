import * as Schema from "effect/Schema";

export const LocalServerConnection = Schema.Struct({
  version: Schema.Literal(1),
  origin: Schema.String,
  pid: Schema.Int,
  bootstrapSecret: Schema.String,
  host: Schema.optional(Schema.String),
  tailscaleServeEnabled: Schema.optional(Schema.Boolean),
  tailscaleServePort: Schema.optional(Schema.Int),
});
export type LocalServerConnection = typeof LocalServerConnection.Type;

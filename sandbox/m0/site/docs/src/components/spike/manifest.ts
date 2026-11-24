// M0 spike (sandbox/m0/site): the shape of a plugin's reference manifest, as the Kotlin build
// writes it. The build also writes the JSON Schema (src/generated/manifest.schema.json), which
// content.config.ts validates every manifest against; in M1 these types are generated from it.
export type PermissionDefault = "op" | "everyone" | "nobody" | "not-op";

export interface Manifest {
  formatVersion: 1;
  plugin: { id: string; name: string; version: string; description: string };
  platforms: Platform[];
  commands: Command[];
  permissions: Permission[];
  config: ConfigNode[];
  messages: { file: string; keys: Message[]; languages: Language[] };
  placeholders: Placeholder[];
  events: ApiEvent[];
}

export interface Platform {
  id: string;
  name: string;
  kind: "server" | "proxy" | "library";
  minVersion: string;
  maxVersion: string;
  java?: number;
  status: "release" | "planned";
  since?: string;
}

export interface Argument {
  name: string;
  type: string;
  required: boolean;
  description: string;
  choices: string[];
  min?: number;
  max?: number;
}

export interface Command {
  path: string;
  usage: string;
  aliases: string[];
  description: string;
  permission?: string;
  playerOnly: boolean;
  arguments: Argument[];
  builtIn: boolean;
  subcommands: Command[];
}

export interface Permission {
  node: string;
  description: string;
  default: PermissionDefault;
  children: Record<string, boolean>;
  commands: string[];
}

export interface ConfigNode {
  path: string;
  type: "section" | "string" | "integer" | "number" | "boolean" | "list" | "duration" | "choice";
  comment: string[];
  default?: unknown;
  choices: string[];
  min?: number;
  max?: number;
  reloadable: boolean;
  children: ConfigNode[];
}

export interface Message {
  key: string;
  default: string;
  placeholders: string[];
  description: string;
}

export interface Language {
  tag: string;
  translated: number;
  total: number;
  missing: string[];
}

export interface Placeholder {
  id: string;
  description: string;
  example: string;
  forms: Record<string, string>;
}

export interface ApiEvent {
  className: string;
  description: string;
  cancellable: boolean;
  fields: { name: string; type: string; description: string }[];
  since: string;
}

/** Every command and subcommand, depth first, as the reference lists them. */
export const flatten = (commands: Command[]): Command[] => commands.flatMap((c) => [c, ...flatten(c.subcommands)]);

export const num = (n: number) => n.toLocaleString("en");

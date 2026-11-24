// A plugin's reference manifest (src/generated/<plugin>/manifest.json), as the rewrite's build
// writes it: core-common's manifest package (Manifest.kt) is the source of truth, and writes
// src/generated/manifest.schema.json from the same shapes, which content.config.ts validates
// every manifest against. Keep these types in step with that schema; a change to the format
// bumps formatVersion, which fails the build until this file and the components follow.
export type PermissionDefault = "op" | "everyone" | "nobody" | "not-op";

export interface Manifest {
  formatVersion: 1;
  plugin: { id: string; name: string; version: string; description: string };
  platforms: Platform[];
  commands: Command[];
  permissions: Permission[];
  config?: Config;
  placeholders: Placeholder[];
  messages: Message[];
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
  greedy: boolean;
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
  executable: boolean;
  cooldown?: string;
  cooldownBypass?: string;
  confirm: boolean;
  builtIn: boolean;
  subcommands: Command[];
}

export interface Permission {
  node: string;
  description: string;
  default: PermissionDefault;
  children: Record<string, boolean>;
  commands: string[];
  builtIn: boolean;
}

export interface Config {
  version: number;
  header: string[];
  entries: ConfigEntry[];
}

export interface ConfigEntry {
  path: string;
  type: string;
  comment: string[];
  default?: unknown;
  defaultText?: string;
  choices: string[];
  min?: number;
  max?: number;
  entries: ConfigEntry[];
}

export interface Placeholder {
  name: string;
  description: string;
  example: string;
  needsPlayer: boolean;
  forms: Record<string, string>;
}

export interface Message {
  key: string;
  english: string;
  comment: string[];
  placeholders: string[];
}

export interface ApiEvent {
  key: string;
  className: string;
  description: string;
  cancellable: boolean;
  since?: string;
}

/** Every command and subcommand, depth first, as the reference lists them. */
export const flatten = (commands: Command[]): Command[] => commands.flatMap((c) => [c, ...flatten(c.subcommands)]);

/** A number as the site writes them: 1,000,000. */
export const num = (n: number) => n.toLocaleString("en");

/** "1 to 10", "at least 1", "up to 10", or nothing. */
export const range = (min?: number, max?: number) =>
  min != null && max != null ? `${num(min)} to ${num(max)}` : min != null ? `at least ${num(min)}` : max != null ? `up to ${num(max)}` : "";

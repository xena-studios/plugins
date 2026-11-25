# Configs are generated from a schema in code (2026-10-21)

A spike showed that Paper's `YamlConfiguration` writes a commented config and reads it back with no server running. (That test is gone; the core's `ConfigLoaderTest` now covers writing and reading configs.) Each plugin declares its settings once (default, validation, comment); the same declaration writes the default `config.yml`, reads and checks the live one, and generates the docs' "full default config" in each plugin's `DefaultConfigTest`, so the docs can't drift from the code.

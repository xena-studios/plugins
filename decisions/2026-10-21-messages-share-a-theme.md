# Messages share a theme (2026-10-21)

Every plugin's config starts with a `theme`: a prefix, a base colour, and `<highlight>`, `<success>` and `<error>` styles that messages use as MiniMessage tags. Default messages are written only in those, so an owner restyles a whole plugin in five lines, and every plugin in the family looks alike out of the box. The base colour wraps each chat message rather than being a tag, so messages don't need to open and close it. A message is parsed the first time it's sent and again only if the theme changes (on reload); messages with placeholders parse per send, as before.

When a default message changes, its old text goes in the spec with `formerDefault`. Carrying an old file forward gives any setting still on an old default the new one, in place, so owners who never touched their messages get the new look and owners who did keep theirs.

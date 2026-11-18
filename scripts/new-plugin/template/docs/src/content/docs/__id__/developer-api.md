---
title: Developer API
description: Use {{display}} from your own plugin.
sidebar:
  order: 5
---

This page is for plugin developers. The API costs nothing unless a plugin uses it.

## Add it to your build

The API is `co.xenastudios.{{id}}:{{id}}-api`. It's inside the {{display}} jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

:::note
The API is published to Maven Central when {{display}} 1.0.0 is released. Until then, build it from [the source](https://github.com/xena-studios/plugins) with `./gradlew :{{id}}:api:publishToMavenLocal` and add `mavenLocal()` to your repositories.
:::

```kotlin
dependencies {
    compileOnly("co.xenastudios.{{id}}:{{id}}-api:1.0.0")
}
```

```xml
<dependency>
    <groupId>co.xenastudios.{{id}}</groupId>
    <artifactId>{{id}}-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs {{display}}, in `plugin.yml`:

```yaml
depend: [{{display}}]      # or softdepend, if your plugin works without it
```

## Get the API

```java
import co.xenastudios.{{id}}.api.{{Class}}Api;

{{Class}}Api {{id}} = {{Class}}Api.get();
```

`{{Class}}Api.get()` throws an `IllegalStateException` if {{display}} isn't enabled. It's also registered with Bukkit's services manager.

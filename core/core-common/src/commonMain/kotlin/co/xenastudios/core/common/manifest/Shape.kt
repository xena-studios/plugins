package co.xenastudios.core.common.manifest

/**
 * One kind of object in the manifest, with its fields in the order they're written. The writer
 * builds every object through its shape ([of]), which refuses an unknown field, a missing
 * required one or a value of the wrong type, and the JSON Schema is made from the same shapes
 * ([ManifestFormat.schema]): the schema can't describe a manifest the writer doesn't write.
 */
internal class Shape(val name: String, val doc: String) {
    class Field(val name: String, val type: JsonType, val doc: String, val required: Boolean)

    val fields = mutableListOf<Field>()

    /** A field that's always written. */
    fun required(name: String, type: JsonType, doc: String) {
        fields += Field(name, type, doc, required = true)
    }

    /** A field that's left out when there's nothing to say. */
    fun optional(name: String, type: JsonType, doc: String) {
        fields += Field(name, type, doc, required = false)
    }

    /** An object of this shape: [values] by field name, in any order; a `null` optional one is left out. */
    fun of(vararg values: Pair<String, Json?>): Json.Obj {
        val given = values.toMap()
        for (key in given.keys) require(fields.any { it.name == key }) { "$name has no field $key" }
        return Json.Obj(
            fields.mapNotNull { field ->
                val value = given[field.name]?.takeIf { it != Json.Null || field.type == JsonType.AnyValue }
                if (value == null) {
                    require(!field.required) { "$name.${field.name} is required" }
                    null
                } else {
                    require(field.type.accepts(value)) { "$name.${field.name} isn't ${field.type}" }
                    field.name to value
                }
            },
            this,
        )
    }
}

/** The type of a manifest field, as the writer checks it and the schema states it. */
internal sealed class JsonType {
    abstract fun accepts(json: Json): Boolean

    /** Its JSON Schema, with other shapes as `$ref`s. */
    abstract fun schema(): Json.Obj

    data object Text : JsonType() {
        override fun accepts(json: Json) = json is Json.Str
        override fun schema() = Json.obj("type" to Json.of("string"))
    }

    data object Integer : JsonType() {
        override fun accepts(json: Json) = json is Json.Num && json.value == kotlin.math.floor(json.value)
        override fun schema() = Json.obj("type" to Json.of("integer"))
    }

    data object Number : JsonType() {
        override fun accepts(json: Json) = json is Json.Num
        override fun schema() = Json.obj("type" to Json.of("number"))
    }

    data object Bool : JsonType() {
        override fun accepts(json: Json) = json is Json.Bool
        override fun schema() = Json.obj("type" to Json.of("boolean"))
    }

    /** Any JSON value, `null` too: a config default. */
    data object AnyValue : JsonType() {
        override fun accepts(json: Json) = true
        override fun schema() = Json.obj()
    }

    class OneOf(val values: List<String>) : JsonType() {
        override fun accepts(json: Json) = json is Json.Str && json.value in values
        override fun schema() = Json.obj("enum" to Json.strings(values))
        override fun toString() = "one of $values"
    }

    class Const(val value: Int) : JsonType() {
        override fun accepts(json: Json) = json is Json.Num && json.value == value.toDouble()
        override fun schema() = Json.obj("const" to Json.of(value))
        override fun toString() = "$value"
    }

    class ArrayOf(val item: JsonType) : JsonType() {
        override fun accepts(json: Json) = json is Json.Arr && json.items.all(item::accepts)
        override fun schema() = Json.obj("type" to Json.of("array"), "items" to item.schema())
        override fun toString() = "an array of $item"
    }

    /** An object with any keys and values of one type. */
    class MapOf(val value: JsonType) : JsonType() {
        override fun accepts(json: Json) = json is Json.Obj && json.members.all { value.accepts(it.second) }
        override fun schema() = Json.obj("type" to Json.of("object"), "additionalProperties" to value.schema())
        override fun toString() = "a map of $value"
    }

    /** An object of another [Shape], looked up when needed, so shapes can refer to themselves. */
    class Ref(private val target: () -> Shape) : JsonType() {
        val shape: Shape get() = target()

        // The object was built by its shape, which checked its own fields.
        override fun accepts(json: Json) = json is Json.Obj && json.shape === shape
        override fun schema() = Json.obj("\$ref" to Json.of("#/\$defs/${shape.name}"))
        override fun toString() = shape.name
    }
}

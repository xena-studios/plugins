@file:OptIn(ExperimentalSerializationApi::class)

package co.xenastudios.m0.site.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A JSON Schema (2020-12) for the manifest, walked from its serializer's descriptors, so the
 * schema can't drift from the classes that write the JSON. Every class is a $def; properties
 * that are never null are required (the writer encodes defaults); @Doc becomes description.
 * No kotlin-reflect: descriptors and @SerialInfo annotations come from the serialization plugin.
 */
fun jsonSchema(root: SerialDescriptor, title: String): JsonObject {
    val defs = linkedMapOf<String, JsonObject>()

    fun doc(annotations: List<Annotation>) = annotations.filterIsInstance<Doc>().firstOrNull()?.text

    fun schemaOf(d: SerialDescriptor): JsonElement {
        val desc = if (d.isNullable) d.nonNullOriginal else d
        if (desc.serialName.startsWith("kotlinx.serialization.json.Json")) return JsonObject(emptyMap())
        return when (val kind: SerialKind = desc.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> obj("type" to "string")
            PrimitiveKind.BOOLEAN -> obj("type" to "boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> obj("type" to "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> obj("type" to "number")
            SerialKind.ENUM -> buildJsonObject {
                doc(desc.annotations)?.let { put("description", it) }
                put("enum", JsonArray((0 until desc.elementsCount).map { JsonPrimitive(desc.getElementName(it)) }))
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaOf(desc.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schemaOf(desc.getElementDescriptor(1)))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val name = desc.serialName.substringAfterLast('.')
                if (name !in defs) {
                    defs[name] = JsonObject(emptyMap()) // placeholder, for recursive classes
                    defs[name] = buildJsonObject {
                        put("type", "object")
                        doc(desc.annotations)?.let { put("description", it) }
                        put("properties", buildJsonObject {
                            for (i in 0 until desc.elementsCount) {
                                // The format version is a constant, so a reader of another version fails validation.
                                val s = if (desc.getElementName(i) == "formatVersion") JsonObject(mapOf("const" to JsonPrimitive(FORMAT_VERSION))) else schemaOf(desc.getElementDescriptor(i))
                                val text = doc(desc.getElementAnnotations(i))
                                put(desc.getElementName(i), if (text == null) s else JsonObject(s.jsonObjectOrEmpty() + ("description" to JsonPrimitive(text))))
                            }
                        })
                        put("required", JsonArray((0 until desc.elementsCount).filter { !desc.getElementDescriptor(it).isNullable }.map { JsonPrimitive(desc.getElementName(it)) }))
                        put("additionalProperties", false)
                    }
                }
                obj("\$ref" to "#/\$defs/$name")
            }
            is PolymorphicKind, SerialKind.CONTEXTUAL -> error("Not in the manifest: ${desc.serialName}")
        }
    }

    val ref = schemaOf(root)
    return buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("title", title)
        put("\$ref", (ref as JsonObject)["\$ref"]!!)
        put("\$defs", JsonObject(defs))
    }
}

private fun JsonElement.jsonObjectOrEmpty(): Map<String, JsonElement> = (this as? JsonObject) ?: emptyMap()

private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

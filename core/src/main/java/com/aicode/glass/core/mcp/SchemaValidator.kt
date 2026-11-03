package com.aicode.glass.core.mcp

import kotlinx.serialization.json.*

/** Conservative JSON Schema subset validation; rejects unknown fields rather than trusting tool hints. */
object SchemaValidator {
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$", depth: Int = 0) {
        require(depth <= 16) { "Tool schema is too deeply nested at $path" }
        schema["enum"]?.jsonArray?.let { require(value in it) { "Invalid enum value at $path" } }
        when(schema["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> {
                require(value is JsonObject) { "$path must be an object" }
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                schema["required"]?.jsonArray?.forEach { require(it.jsonPrimitive.content in value) { "Missing ${it.jsonPrimitive.content} at $path" } }
                if(schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) require(value.keys.all { it in properties }) { "Unexpected property at $path" }
                value.forEach { (key, child) -> properties[key]?.jsonObject?.let { validate(it, child, "$path.$key", depth + 1) } }
            }
            "array" -> { require(value is JsonArray) { "$path must be an array" }; schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size <= it) }; value.forEachIndexed { i, child -> (schema["items"] as? JsonObject)?.let { validate(it, child, "$path[$i]", depth + 1) } } }
            "string" -> { require(value is JsonPrimitive && value.isString) { "$path must be a string" }; schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { require(value.content.length <= it) { "$path is too long" } } }
            "integer" -> require(value is JsonPrimitive && value.contentOrNull?.toLongOrNull() != null) { "$path must be an integer" }
            "number" -> require(value is JsonPrimitive && value.contentOrNull?.toDoubleOrNull()?.isFinite() == true) { "$path must be a number" }
            "boolean" -> require(value is JsonPrimitive && value.booleanOrNull != null) { "$path must be a boolean" }
            "null" -> require(value is JsonNull) { "$path must be null" }
        }
    }
}

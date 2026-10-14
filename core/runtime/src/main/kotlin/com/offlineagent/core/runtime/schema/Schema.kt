package com.offlineagent.core.runtime.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A small, typed subset of JSON Schema. One definition is used three ways, so they can never drift apart:
 *  1. [Gbnf.of] turns it into a llama.cpp GBNF grammar (constrained sampling: the model can only emit matching JSON);
 *  2. [validate] checks a parsed value in trusted Kotlin code (ranges, lengths, enums) before anything executes;
 *  3. [toJsonSchema] describes it to the model / the user (tool documentation).
 *
 * Objects use a FIXED property order and every property is required: that keeps grammars small and
 * makes small models far more reliable. Optional data is expressed with empty strings / arrays.
 */
sealed class Schema {
    abstract val description: String?

    data class Str(
        override val description: String? = null,
        val maxLength: Int = 4000,
        val minLength: Int = 0,
        val enum: List<String>? = null,
    ) : Schema()

    data class Num(
        override val description: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val integer: Boolean = false,
    ) : Schema()

    data class Bool(override val description: String? = null) : Schema()

    data class Arr(
        val items: Schema,
        override val description: String? = null,
        val minItems: Int = 0,
        val maxItems: Int = 20,
    ) : Schema()

    data class Obj(
        val properties: List<Pair<String, Schema>>,
        override val description: String? = null,
    ) : Schema() {
        fun prop(name: String): Schema? = properties.firstOrNull { it.first == name }?.second
    }

    /** Exactly one alternative. Alternatives must be objects with a distinguishing `const` property. */
    data class OneOf(val options: List<Schema>, override val description: String? = null) : Schema()

    /** A fixed string value, e.g. a block's "type". */
    data class Const(val value: String, override val description: String? = null) : Schema()

    /** Any JSON object (used only where a nested schema is validated elsewhere). */
    data class AnyObj(override val description: String? = null) : Schema()

    fun toJsonSchema(): JsonObject = when (this) {
        is Str -> buildJsonObject {
            put("type", "string")
            enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
            put("maxLength", maxLength)
            description?.let { put("description", it) }
        }
        is Num -> buildJsonObject {
            put("type", if (integer) "integer" else "number")
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
            description?.let { put("description", it) }
        }
        is Bool -> buildJsonObject { put("type", "boolean"); description?.let { put("description", it) } }
        is Arr -> buildJsonObject {
            put("type", "array"); put("items", items.toJsonSchema()); put("maxItems", maxItems)
            if (minItems > 0) put("minItems", minItems)
            description?.let { put("description", it) }
        }
        is Obj -> buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.associate { it.first to it.second.toJsonSchema() }))
            put("required", buildJsonArray { properties.forEach { add(JsonPrimitive(it.first)) } })
            description?.let { put("description", it) }
        }
        is OneOf -> buildJsonObject { put("oneOf", buildJsonArray { options.forEach { add(it.toJsonSchema()) } }) }
        is Const -> buildJsonObject { put("const", value) }
        is AnyObj -> buildJsonObject { put("type", "object") }
    }

    /** Compact one-line signature for prompts, e.g. {"query": string, "k": integer 1..8}. */
    fun signature(): String = when (this) {
        is Str -> enum?.joinToString("|", "\"", "\"") { it } ?: "string"
        is Num -> (if (integer) "integer" else "number") + if (min != null && max != null) " ${fmt(min)}..${fmt(max)}" else ""
        is Bool -> "boolean"
        is Arr -> "[${items.signature()}, ...≤$maxItems]"
        is Obj -> properties.joinToString(", ", "{", "}") { "\"${it.first}\": ${it.second.signature()}" }
        is OneOf -> options.joinToString(" | ") { it.signature() }
        is Const -> "\"$value\""
        is AnyObj -> "object"
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

data class ValidationError(val path: String, val message: String) {
    override fun toString() = "$path: $message"
}

/** Validates [value] against [schema]. Returns all errors (empty = valid). */
fun Schema.validate(value: JsonElement?, path: String = "$"): List<ValidationError> {
    val errs = mutableListOf<ValidationError>()
    fun err(m: String) { errs += ValidationError(path, m) }
    when (val s = this) {
        is Schema.Str -> {
            val p = value as? JsonPrimitive
            if (p == null || !p.isString) { err("expected string"); return errs }
            val v = p.content
            if (v.length > s.maxLength) err("longer than ${s.maxLength} characters")
            if (v.length < s.minLength) err("shorter than ${s.minLength} characters")
            if (s.enum != null && v !in s.enum) err("must be one of ${s.enum}")
        }
        is Schema.Num -> {
            val p = value as? JsonPrimitive
            val d = if (p != null && !p.isString) p.doubleOrNull else null
            if (d == null || d.isNaN() || d.isInfinite()) { err("expected number"); return errs }
            if (s.integer && (p!!.longOrNull == null && d != Math.floor(d))) err("expected integer")
            if (s.min != null && d < s.min) err("must be >= ${s.min}")
            if (s.max != null && d > s.max) err("must be <= ${s.max}")
        }
        is Schema.Bool -> if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) err("expected boolean")
        is Schema.Arr -> {
            val a = value as? JsonArray ?: run { err("expected array"); return errs }
            if (a.size > s.maxItems) err("more than ${s.maxItems} items")
            if (a.size < s.minItems) err("fewer than ${s.minItems} items")
            a.forEachIndexed { i, e -> errs += s.items.validate(e, "$path[$i]") }
        }
        is Schema.Obj -> {
            val o = value as? JsonObject ?: run { err("expected object"); return errs }
            for ((name, ps) in s.properties) {
                if (name !in o) errs += ValidationError("$path.$name", "missing") else errs += ps.validate(o[name], "$path.$name")
            }
            (o.keys - s.properties.map { it.first }.toSet()).forEach { errs += ValidationError("$path.$it", "unknown property") }
        }
        is Schema.OneOf -> {
            val results = s.options.map { it.validate(value, path) }
            if (results.none { it.isEmpty() }) {
                // report against the alternative whose discriminator matches, if any
                val best = results.minByOrNull { it.size }!!
                errs += best
            }
        }
        is Schema.Const -> if ((value as? JsonPrimitive)?.content != s.value || !(value as JsonPrimitive).isString) err("must be \"${s.value}\"")
        is Schema.AnyObj -> if (value !is JsonObject) err("expected object")
    }
    return errs
}

internal fun JsonElement?.isNullish() = this == null || this is JsonNull

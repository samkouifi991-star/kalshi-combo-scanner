package com.samer.comboscanner.engine.kalshi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

internal fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
internal fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
internal fun JsonObject.dbl(key: String): Double? = str(key)?.toDoubleOrNull()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/**
 * Price in cents for a field that Kalshi may send as integer cents ("yes_ask": 92)
 * or as a dollar string ("yes_ask_dollars": "0.9200").
 */
internal fun JsonObject.cents(field: String): Double? =
    dbl("${field}_dollars")?.let { it * 100 } ?: dbl(field)

internal fun JsonObject.timeMs(key: String): Long? =
    str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

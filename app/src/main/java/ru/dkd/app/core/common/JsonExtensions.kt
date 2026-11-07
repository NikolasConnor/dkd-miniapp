package ru.dkd.app.core.common

import org.json.JSONObject

internal fun JSONObject.string(key: String, fallback: String = ""): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" } ?: fallback

internal fun JSONObject.int(key: String): Int = optInt(key, 0)

internal fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)

internal fun JSONObject.array(key: String): List<JSONObject> {
    val value = optJSONArray(key) ?: return emptyList()
    return (0 until value.length()).mapNotNull { value.optJSONObject(it) }
}

internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

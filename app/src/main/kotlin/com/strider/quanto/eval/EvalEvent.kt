package com.strider.quanto.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object EvalEvent {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun nowIso(): String = isoFormatter.format(Instant.now())

    fun toJsonLine(event: String, props: Map<String, Any?>): String {
        val payload = linkedMapOf<String, Any?>(
            "ts" to nowIso(),
            "event" to event,
            "session_id" to EvalSession.sessionId
        )
        payload.putAll(props)
        return gson.toJson(payload)
    }
}

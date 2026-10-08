package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.network.DiscoveredModel
import org.json.JSONArray
import org.json.JSONObject

/** Saved model lists, one per agent, provider and endpoint. Plain JSON so it can be tested without Android. */
internal object ModelListCache {

    /** Fixed-endpoint providers ignore whatever URL was typed last. */
    fun key(agent: AgentKind, kind: ProviderKind, baseUrl: String): String {
        val endpoint = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim().trimEnd('/')
        return "model_list_v1|${agent.stableId}|${kind.name}|$endpoint"
    }

    fun encode(models: List<DiscoveredModel>): String {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.displayName)
                    .put("efforts", JSONArray(model.reasoningEfforts)),
            )
        }
        return array.toString()
    }

    /** Corrupt or missing data reads as an empty list; the user can simply run Discover again. */
    fun decode(raw: String?): List<DiscoveredModel> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id").trim()
                    if (id.isEmpty()) continue
                    val efforts = obj.optJSONArray("efforts")
                    val levels = buildList {
                        for (level in 0 until (efforts?.length() ?: 0)) {
                            efforts?.optString(level)?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                    add(
                        DiscoveredModel(
                            id = id,
                            displayName = obj.optString("name").ifBlank { id },
                            reasoningEfforts = levels,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

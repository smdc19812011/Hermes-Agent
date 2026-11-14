package com.hermes.agent.data.agents

import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.Skill

/** Pure parts of skills and agents, kept off Android so they can be unit-tested. */
object SkillFormat {
    /** Folder-safe skill name: lowercase letters, digits and hyphens, as Hermes uses. */
    fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64)

    fun parse(text: String, fallbackName: String): Skill {
        val lines = text.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return Skill(fallbackName, "", text.trim())
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1)
            ?: return Skill(fallbackName, "", text.trim())
        val meta = lines.subList(1, end).mapNotNull { line ->
            val i = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, i).trim() to line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }.toMap()
        return Skill(meta["name"]?.ifBlank { null } ?: fallbackName, meta["description"].orEmpty(), lines.drop(end + 1).joinToString("\n").trim())
    }

    fun render(skill: Skill): String {
        val description = skill.description.replace('\n', ' ').trim()
        return "---\nname: ${slug(skill.name)}\ndescription: ${if (description.contains(": ") || description.startsWith("\"")) "\"${description.replace("\"", "'")}\"" else description}\n---\n\n${skill.body.trim()}\n"
    }
}

object AgentRouting {
    /**
     * Turns an agent's model list into (provider, model) pairs. "Provider name: model" picks that provider (model IDs may contain
     * colons, like ":free", so the prefix only counts when it names a provider). A bare model goes to the provider that lists it,
     * else the first OpenRouter provider, else the first provider.
     */
    fun resolve(models: List<String>, providers: List<Provider>): List<Pair<Provider, String>> = models.mapNotNull { raw ->
        val entry = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val prefix = entry.substringBefore(':', "").trim()
        val named = providers.firstOrNull { prefix.isNotEmpty() && (it.name.equals(prefix, true) || it.type.displayName.equals(prefix, true)) }
        if (named != null) return@mapNotNull named to entry.substringAfter(':').trim()
        val provider = providers.firstOrNull { entry in it.models }
            ?: providers.firstOrNull { it.type == com.hermes.agent.domain.model.ProviderType.OPENROUTER }
            ?: providers.firstOrNull()
        provider?.let { it to entry }
    }.distinct()
}

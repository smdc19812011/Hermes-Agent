package com.hermes.agent.data.agents

import com.google.gson.JsonParser
import com.hermes.agent.domain.model.AgentProfile
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.tools.Capability
import com.hermes.agent.domain.tools.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentLogicTest {
    private fun provider(name: String, type: ProviderType, vararg models: String) =
        Provider(name, name, type, "k", "", models.toList(), true, 0)

    private val or1 = provider("OR1", ProviderType.OPENROUTER, "nvidia/nemotron-3.5-ultra:free")
    private val groq = provider("Groq main", ProviderType.GROQ, "llama-4-scout")

    @Test
    fun skillRoundTripsThroughSkillMd() {
        val skill = Skill("Find ADB devices", "Finding Android devices: phones, TVs", "1. Run scan_lan\n2. Look for 5555/adb")
        val text = SkillFormat.render(skill)
        assert(text.startsWith("---\nname: find-adb-devices\ndescription: \"Finding Android devices: phones, TVs\"\n---\n"))
        assertEquals(Skill("find-adb-devices", "Finding Android devices: phones, TVs", skill.body), SkillFormat.parse(text, "x"))
    }

    @Test
    fun parsesHermesSkillsAndPlainMarkdown() {
        val hermes = "---\nname: arxiv\ndescription: Search arXiv papers\nversion: 1.0.0\nmetadata:\n  tags: [research]\n---\n\n# arXiv\nUse the API."
        assertEquals(Skill("arxiv", "Search arXiv papers", "# arXiv\nUse the API."), SkillFormat.parse(hermes, "folder"))
        assertEquals(Skill("folder", "", "Just steps."), SkillFormat.parse("Just steps.", "folder"))
        assertEquals("", SkillFormat.slug("!!!"))
        assertEquals("my-skill-2", SkillFormat.slug("  My Skill #2 "))
    }

    @Test
    fun resolvesAgentModelsToProviders() {
        val providers = listOf(groq, or1)
        assertEquals(
            listOf(
                groq to "llama-4-scout",                       // provider by name
                or1 to "google/gemini-3-flash:free",           // colon in model id, no provider prefix
                groq to "llama-4-scout",                       // duplicate removed below
                or1 to "nvidia/nemotron-3.5-ultra:free"        // bare model goes to the provider listing it
            ).distinct(),
            AgentRouting.resolve(listOf("Groq main: llama-4-scout", "google/gemini-3-flash:free", "groq main:llama-4-scout", "nvidia/nemotron-3.5-ultra:free", " "), providers)
        )
        assertEquals(listOf(groq to "x"), AgentRouting.resolve(listOf("Groq: x"), providers)) // provider type name works too
        assertEquals(emptyList<Pair<Provider, String>>(), AgentRouting.resolve(listOf("m"), emptyList()))
    }

    @Test
    fun agentProfileJsonKeepsUnsetAccessDistinctFromNone() {
        val open = AgentProfile(name = "Scout", instructions = "Map the LAN", models = listOf("OR1: a"), tools = listOf("scan_lan"))
        assertEquals(open, AgentProfile.fromJson(open.toJson()))
        assertNull(AgentProfile.fromJson(open.toJson())!!.deviceAccess)
        val limited = open.copy(deviceAccess = setOf(Capability.NETWORK, Capability.AGENTS), createdByAi = true)
        assertEquals(limited, AgentProfile.fromJson(limited.toJson()))
        assertEquals(emptySet<Capability>(), AgentProfile.fromJson(open.copy(deviceAccess = emptySet()).toJson())!!.deviceAccess)
        assertNull(AgentProfile.fromJson("not json"))
    }

    @Test
    fun toolArgumentListsAcceptArraysOrCommaText() {
        val args = JsonParser.parseString("""{"a":["x"," y ",""],"b":"scan_lan, port_scan","c":3}""").asJsonObject
        assertEquals(listOf("x", "y"), args.strings("a"))
        assertEquals(listOf("scan_lan", "port_scan"), args.strings("b"))
        assertEquals(listOf("3"), args.strings("c"))
        assertEquals(emptyList<String>(), args.strings("missing"))
    }
}

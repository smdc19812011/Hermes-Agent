package com.hermes.agent.data.agents

import android.content.Context
import com.hermes.agent.domain.model.AgentProfile
import com.hermes.agent.domain.model.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Skills (skills/<name>/SKILL.md, the layout Hermes uses) and agent profiles (agents/<id>.json), shared by every chat. */
@Singleton
class AgentLibraryRepository @Inject constructor(@ApplicationContext context: Context) {
    private val skillsDir = File(context.filesDir, "skills")
    private val agentsDir = File(context.filesDir, "agents")
    private val lock = Any()

    private val _skills = MutableStateFlow(loadSkills())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
    private val _agents = MutableStateFlow(loadAgents())
    val agents: StateFlow<List<AgentProfile>> = _agents.asStateFlow()

    private fun loadSkills(): List<Skill> = skillsDir.listFiles().orEmpty()
        .mapNotNull { dir -> File(dir, "SKILL.md").takeIf { it.isFile }?.let { SkillFormat.parse(it.readText(), dir.name) } }
        .sortedBy { it.name }

    private fun loadAgents(): List<AgentProfile> = agentsDir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { AgentProfile.fromJson(it.readText()) }
        .sortedBy { it.name.lowercase() }

    fun skill(name: String): Skill? = SkillFormat.slug(name).let { slug -> _skills.value.firstOrNull { SkillFormat.slug(it.name) == slug } }

    /** Creates or replaces a skill; a rename moves it to the new folder. */
    suspend fun saveSkill(skill: Skill, previousName: String? = null): Skill = withContext(Dispatchers.IO) {
        val slug = SkillFormat.slug(skill.name)
        require(slug.isNotEmpty()) { "A skill needs a name made of letters or digits." }
        require(skill.body.isNotBlank()) { "A skill needs instructions." }
        val saved = skill.copy(name = slug)
        synchronized(lock) {
            previousName?.let(SkillFormat::slug)?.takeIf { it != slug }?.let { File(skillsDir, it).deleteRecursively() }
            File(skillsDir, slug).apply { mkdirs() }.resolve("SKILL.md").writeText(SkillFormat.render(saved))
            _skills.value = loadSkills()
        }
        saved
    }

    suspend fun deleteSkill(name: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            File(skillsDir, SkillFormat.slug(name)).deleteRecursively()
            _skills.value = loadSkills()
        }
    }

    fun agent(id: String?): AgentProfile? = id?.let { i -> _agents.value.firstOrNull { it.id == i } }

    fun agentNamed(name: String): AgentProfile? = _agents.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    suspend fun saveAgent(agent: AgentProfile): AgentProfile = withContext(Dispatchers.IO) {
        require(agent.name.isNotBlank()) { "An agent needs a name." }
        require(_agents.value.none { it.id != agent.id && it.name.equals(agent.name.trim(), true) }) { "There's already an agent called ${agent.name.trim()}." }
        val saved = agent.copy(name = agent.name.trim())
        synchronized(lock) {
            agentsDir.mkdirs()
            File(agentsDir, "${saved.id}.json").writeText(saved.toJson())
            _agents.value = loadAgents()
        }
        saved
    }

    suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            File(agentsDir, "$id.json").delete()
            _agents.value = loadAgents()
        }
    }
}

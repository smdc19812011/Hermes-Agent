package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.domain.model.Conversation
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.toDomain
import com.hermes.agent.domain.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun observeAll(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { it.map { e -> e.toDomain() } }

    fun search(query: String): Flow<List<Conversation>> =
        conversationDao.search(query).map { it.map { e -> e.toDomain() } }

    suspend fun getById(id: String): Conversation? = conversationDao.getById(id)?.toDomain()

    fun observeById(id: String): Flow<Conversation?> = conversationDao.observeById(id).map { it?.toDomain() }

    suspend fun save(conversation: Conversation) = conversationDao.upsert(conversation.toEntity())

    suspend fun delete(conversation: Conversation) {
        messageDao.deleteForConversation(conversation.id)
        conversationDao.delete(conversation.toEntity())
    }

    suspend fun updateTitle(id: String, title: String) = conversationDao.updateTitle(id, title)

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId).map { it.map { e -> e.toDomain() } }

    suspend fun getMessages(conversationId: String): List<Message> =
        messageDao.getForConversation(conversationId).map { it.toDomain() }

    suspend fun insertMessage(message: Message) {
        messageDao.insert(message.toEntity())
        conversationDao.incrementMessageCount(
            id = message.conversationId,
            lastMessage = message.content.take(100)
        )
    }

    suspend fun updateMessageContent(id: String, content: String) =
        messageDao.updateContent(id, content)

    suspend fun deleteMessage(id: String) = messageDao.deleteById(id)
}

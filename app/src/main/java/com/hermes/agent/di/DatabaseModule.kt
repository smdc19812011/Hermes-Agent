package com.hermes.agent.di

import android.content.Context
import androidx.room.Room
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HermesDatabase =
        Room.databaseBuilder(context, HermesDatabase::class.java, "hermes.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideConversationDao(db: HermesDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: HermesDatabase): MessageDao = db.messageDao()
    @Provides fun provideProviderDao(db: HermesDatabase): ProviderDao = db.providerDao()
}

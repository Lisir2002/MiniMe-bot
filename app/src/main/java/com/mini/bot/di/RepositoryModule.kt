package com.mini.bot.di

import com.mini.bot.feature.settings.domain.repository.AIProviderRepository
import com.mini.bot.feature.settings.data.repository.AIProviderRepositoryImpl
import com.mini.bot.feature.credentials.domain.repository.CredentialRepository
import com.mini.bot.feature.credentials.data.repository.FileCredentialRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAIProviderRepository(
        aiProviderRepositoryImpl: AIProviderRepositoryImpl
    ): AIProviderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        fileCredentialRepository: FileCredentialRepository
    ): CredentialRepository
}

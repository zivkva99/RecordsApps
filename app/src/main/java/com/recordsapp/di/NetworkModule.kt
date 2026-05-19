package com.recordsapp.di

import com.recordsapp.data.remote.GeminiRecognitionService
import com.recordsapp.data.remote.RecognitionService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindRecognitionService(impl: GeminiRecognitionService): RecognitionService
}

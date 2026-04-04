package com.recordsapp.di

import android.content.Context
import androidx.room.Room
import com.recordsapp.data.local.RecordsDatabase
import com.recordsapp.data.local.dao.AlbumDao
import com.recordsapp.data.local.dao.CopyDao
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
    fun provideDatabase(@ApplicationContext context: Context): RecordsDatabase {
        return Room.databaseBuilder(
            context,
            RecordsDatabase::class.java,
            "records_database"
        ).build()
    }

    @Provides
    fun provideAlbumDao(database: RecordsDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideCopyDao(database: RecordsDatabase): CopyDao = database.copyDao()
}

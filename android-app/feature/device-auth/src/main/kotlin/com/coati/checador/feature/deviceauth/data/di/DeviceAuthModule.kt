package com.coati.checador.feature.deviceauth.data.di

import com.coati.checador.feature.deviceauth.data.repository.DeviceAuthRepositoryImpl
import com.coati.checador.feature.deviceauth.domain.repository.DeviceAuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceAuthModule {

    @Binds
    @Singleton
    abstract fun bindDeviceAuthRepository(
        impl: DeviceAuthRepositoryImpl
    ): DeviceAuthRepository
}

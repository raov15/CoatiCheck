package com.coati.checador.feature.employeeenrollment.data.di

import com.coati.checador.feature.employeeenrollment.data.repository.RepositorioRegistroEmpleadoImpl
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt que vincula la interfaz de repositorio con su implementación concreta.
 * Instalado en [SingletonComponent] para ciclo de vida de la aplicación.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnrollmentModule {

    @Binds
    @Singleton
    abstract fun bindRepositorioRegistroEmpleado(
        impl: RepositorioRegistroEmpleadoImpl
    ): RepositorioRegistroEmpleado
}

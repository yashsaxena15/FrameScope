package com.merlin.perfmon.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * All singletons in this app (collectors, repositories, shell managers,
 * OverlayManager) are annotated with both @Singleton and @Inject constructor,
 * so Hilt resolves the entire dependency graph automatically.
 *
 * No manual @Provides bindings are needed. This module is kept as a placeholder
 * for any future bindings that can't use constructor injection (e.g. interfaces,
 * third-party classes without @Inject).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule

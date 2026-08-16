package com.dimroom.di

import javax.inject.Qualifier

/**
 * Targets are spelled out deliberately, and `PROPERTY` is deliberately absent.
 *
 * With no explicit target, Kotlin attaches a qualifier to the *property* rather than the backing
 * field, and Dagger — which reads the field — then sees an unqualified `CoroutineDispatcher` and
 * fails to resolve the binding. Listing FIELD, VALUE_PARAMETER and FUNCTION makes constructor
 * parameters, `@Inject` fields and `@Provides` methods all resolve the way they read.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class DefaultDispatcher

/** The root directory owned by [com.dimroom.data.storage.LocalStorageProvider]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class LibraryRoot

/** Application-scoped coroutine scope for work that must outlive a screen (imports, exports). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class ApplicationScope

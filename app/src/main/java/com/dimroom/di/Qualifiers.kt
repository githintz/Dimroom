package com.dimroom.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** The root directory owned by [com.dimroom.data.storage.LocalStorageProvider]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibraryRoot

/** Application-scoped coroutine scope for work that must outlive a screen (imports, exports). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

package com.blazepush.feature.test.di

import com.blazepush.core.domain.usecase.GpsDataFilter
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.get

class DomainModuleKoinTest {

    @Test
    fun domainModule_providesGpsDataFilter() {
        stopKoin()
        startKoin {
            modules(domainModule)
        }

        try {
            val filter = get<GpsDataFilter>(GpsDataFilter::class.java)
            assertNotNull(filter)
        } finally {
            stopKoin()
        }
    }
}

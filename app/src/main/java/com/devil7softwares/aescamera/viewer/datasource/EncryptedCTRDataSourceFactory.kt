package com.devil7softwares.aescamera.viewer.datasource

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

@UnstableApi
class EncryptedCTRDataSourceFactory(private val password: String) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return EncryptedCTRDataSource(password)
    }
}

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource

fun test(httpFactory: DataSource.Factory) {
    val customHttpFactory = DataSource.Factory {
        val dataSource = httpFactory.createDataSource()
        object : DataSource by dataSource {
            override fun open(dataSpec: DataSpec): Long {
                try {
                    return dataSource.open(dataSpec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    throw java.io.IOException("Custom Http Error", e)
                }
            }
        }
    }
}

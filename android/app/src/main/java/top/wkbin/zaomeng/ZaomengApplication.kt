package top.wkbin.zaomeng

import com.chaquo.python.android.PyApplication
import top.wkbin.zaomeng.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class ZaomengApplication : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@ZaomengApplication)
            modules(appModule)
        }
    }
}

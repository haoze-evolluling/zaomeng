package top.wkbin.zaomeng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.navigation.ZaomengApp
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferencesRepository: AppPreferencesRepository = koinInject()
            val themeMode = preferencesRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = top.wkbin.zaomeng.data.preferences.ThemeMode.SYSTEM,
            ).value
            MyApplicationTheme(themeMode = themeMode, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ZaomengApp()
                }
            }
        }
    }
}

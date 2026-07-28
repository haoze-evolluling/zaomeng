package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import top.wkbin.zaomeng.backend.EmbeddedBackendController
import top.wkbin.zaomeng.backend.InstallationTokenStore
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.LocalApiFactory
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.persona.PersonaViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { androidContext().preferencesDataStoreFile("zaomeng.preferences_pb") },
        )
    }
    single { InstallationTokenStore(androidContext()) }
    single { LocalApiFactory() }
    single {
        EmbeddedBackendController(
            context = androidContext(),
            tokenStore = get(),
            apiFactory = get(),
        )
    }
    single { AppPreferencesRepository(get()) }
    single { ZaomengRepository(get(), get()) }

    viewModel { BookshelfViewModel(get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { ImportBookViewModel(get(), androidContext()) }
    viewModel { parameters -> RunDetailViewModel(get(), parameters.get(), androidContext()) }
    viewModel { parameters -> RedistillViewModel(get(), parameters.get(), androidContext()) }
    viewModel { parameters -> RelationsViewModel(get(), parameters.get()) }
    viewModel { CardLibraryViewModel(get()) }
    viewModel { PersonaViewModel(get()) }
    viewModel { SessionsViewModel(get()) }
    viewModel { ChatViewModel(get()) }
}

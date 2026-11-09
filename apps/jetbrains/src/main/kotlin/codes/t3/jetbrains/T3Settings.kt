package codes.t3.jetbrains

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "T3CodeConnection", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class T3Settings : PersistentStateComponent<T3Settings.Data> {
    class Data {
        var serverUrl: String = "http://localhost:3773/"
        var serverExecutable: String = ""
        var serverEntryScript: String = ""
        var autoStartServer: Boolean = false
        var sharedHome: String = ""
    }

    private var data = Data()
    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }
}

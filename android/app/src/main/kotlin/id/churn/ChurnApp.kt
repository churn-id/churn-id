package id.churn

import android.app.Application
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class ChurnApp : Application() {

    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        // sshj asks for the provider named "BC". Android's own provider of that name lacks algorithms sshj
        // needs, so the full Bouncy Castle provider takes its place, last in line: everything else keeps using
        // Android's providers.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        controller = Controller(this)
    }
}

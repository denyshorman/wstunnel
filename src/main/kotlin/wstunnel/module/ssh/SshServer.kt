package wstunnel.module.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.forward.DefaultForwarderFactory
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionHeartbeatController
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration

class SshServer(
    private val login: String? = null,
    private val password: String? = null,
) {
    private val sshServer = SshServer.setUpDefaultServer()

    init {
        sshServer.keyPairProvider = keyPairProvider()
        sshServer.publickeyAuthenticator = publicKeyAuthenticator()
        sshServer.passwordAuthenticator = passwordAuthenticator()
        sshServer.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        sshServer.forwarderFactory = DefaultForwarderFactory.INSTANCE
        sshServer.ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
        sshServer.shellFactory = InteractiveProcessShellFactory.INSTANCE
        sshServer.commandFactory = ProcessShellCommandFactory.INSTANCE
        sshServer.subsystemFactories = listOf(SftpSubsystemFactory())
        sshServer.fileSystemFactory = NativeFileSystemFactory.INSTANCE

        sshServer.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, Duration.ofMinutes(1))
        PropertyResolverUtils.updateProperty(sshServer, "idle-timeout", Duration.ofMinutes(1).toMillis())
    }

    val port: Int get() = sshServer.port

    suspend fun start(
        host: String = "0.0.0.0",
        port: Int = 0
    ) {
        sshServer.port = port
        sshServer.host = host

        withContext(Dispatchers.IO) {
            sshServer.start()
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            sshServer.stop()
        }
    }

    private fun keyPairProvider(): KeyPairProvider {
        val generator = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        generator.initialize(ecSpec)
        val keyPair = generator.generateKeyPair()
        return KeyPairProvider.wrap(keyPair)
    }

    private fun publicKeyAuthenticator(): PublickeyAuthenticator {
        return if (login != null) {
            PublickeyAuthenticator { username, _, _ -> username == login }
        } else {
            PublickeyAuthenticator { _, _, _ -> true }
        }
    }

    private fun passwordAuthenticator(): PasswordAuthenticator {
        return when {
            login != null && password != null -> {
                PasswordAuthenticator { username, pwd, _ -> username == login && pwd == password }
            }

            login != null -> {
                PasswordAuthenticator { username, _, _ -> username == login }
            }

            password != null -> {
                PasswordAuthenticator { _, pwd, _ -> pwd == password }
            }

            else -> {
                PasswordAuthenticator { _, _, _ -> true }
            }
        }
    }
}

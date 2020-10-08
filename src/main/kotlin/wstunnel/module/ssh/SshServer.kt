package wstunnel.module.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.FactoryManager
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
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.time.minutes
import kotlin.time.toJavaDuration

class SshServer {
    private val sshServer = SshServer.setUpDefaultServer()

    init {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom(byteArrayOf(0)))
        val keyPair = generator.generateKeyPair()
        val keyPairProvider = KeyPairProvider.wrap(keyPair)

        sshServer.keyPairProvider = keyPairProvider
        sshServer.publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> true }
        sshServer.passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
        sshServer.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        sshServer.forwarderFactory = DefaultForwarderFactory.INSTANCE
        sshServer.ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
        sshServer.shellFactory = InteractiveProcessShellFactory.INSTANCE
        sshServer.commandFactory = ProcessShellCommandFactory.INSTANCE
        sshServer.subsystemFactories = listOf(SftpSubsystemFactory())
        sshServer.fileSystemFactory = NativeFileSystemFactory.INSTANCE

        sshServer.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, 1.minutes.toJavaDuration())
        PropertyResolverUtils.updateProperty(sshServer, FactoryManager.IDLE_TIMEOUT, 2.minutes.toLongMilliseconds())
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
}

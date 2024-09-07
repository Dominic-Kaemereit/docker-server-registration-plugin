package me.d151l.docker.registration.plugin

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import com.google.inject.Inject
import com.google.inject.Injector
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit


class VelocityPlugin  @Inject constructor(
    var proxyServer: ProxyServer,
    var logger: Logger,
    private val injector: Injector,
    private val container: PluginContainer,
    @DataDirectory val dataDirectory: Path,
) {

    private var standard: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost("unix:///var/run/docker.sock")
        .build()
    private var httpClient: DockerHttpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(standard.getDockerHost())
        .sslConfig(standard.getSSLConfig())
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(45))
        .build()
    private var dockerClient: DockerClient = DockerClientImpl.getInstance(standard, httpClient)

    private val miniMessage = MiniMessage.miniMessage()

    @Subscribe
    fun onEnable(event: ProxyInitializeEvent) {
        proxyServer.allServers.forEach {
            proxyServer.unregisterServer(it.serverInfo)
        }

        proxyServer.scheduler.buildTask(
            this,
            Runnable { updateRegisterServer() }
        ).repeat(10, TimeUnit.SECONDS).schedule()
    }

    private fun updateRegisterServer() {
        val containers = dockerClient.listContainersCmd().exec()

        val servers = mutableListOf<Service>()

        containers.forEach {
            if (it.networkSettings == null) return@forEach
            if (it.networkSettings!!.networks["minecraft-network"] == null) return@forEach
            if (it.networkSettings!!.networks["minecraft-network"]!!.ipAddress == null) return@forEach

            if (it.names.isEmpty()) return@forEach

            val name = it.names[0].substring(1)
            val ip = it.networkSettings!!.networks["minecraft-network"]!!.ipAddress!!
            val port = 25565

            if (name.contains("proxy")) return@forEach

            val shortImageID = if (it.imageId != null) it.imageId!!.substring(0, 5) else "unknown"
            val shortContainerID = it.id.substring(0, 5)

            val serviceName = "$name-$shortImageID-$shortContainerID"

            servers.add(Service(serviceName, ip, port))
        }

        proxyServer.allServers.forEach {
            if (!servers.map { it.name }.contains(it.serverInfo.name)) {
                proxyServer.unregisterServer(it.serverInfo)
                logger.info("Unregistered server ${it.serverInfo.name}")
            } else {
                servers.removeIf { server -> server.name == it.serverInfo.name }
            }
        }

        servers.forEach {
            val serverInfo = ServerInfo(it.name, InetSocketAddress(it.host, it.port))
            proxyServer.registerServer(serverInfo)
            logger.info("Registered server ${it.name}")
        }
    }


    @Subscribe
    fun onPlayerChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val hubServer = getHubServer(event.player)

        if (hubServer == null) {
            event.player.disconnect(miniMessage.deserialize("<red>There are no servers available. Please try again later."))
            return
        }

        event.setInitialServer(hubServer)
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        if (event.player.currentServer.isEmpty) {
            return
        }

        val hubServer = getHubServer(event.player)

        if (hubServer == null) {
            event.player.disconnect(miniMessage.deserialize("<red>There are no servers available. Please try again later."))
            return
        }

        event.result = RedirectPlayer.create(hubServer)
    }

    private fun getHubServer(player: Player): RegisteredServer? {
        var registeredServers = proxyServer.allServers.filter { it.serverInfo.name.contains("hub") }

        if (player.currentServer.isPresent) {
            registeredServers = registeredServers.filter { it.serverInfo.name != player.currentServer.get().serverInfo.name }
        }

        if (registeredServers.isEmpty()) {
            player.disconnect(miniMessage.deserialize("<red>There are no servers available. Please try again later."))
            return null
        }

        return registeredServers.random()
    }
}
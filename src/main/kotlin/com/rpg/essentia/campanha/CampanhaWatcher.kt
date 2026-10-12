package com.rpg.essentia.campanha

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Observa a pasta da campanha (raiz e capítulos). Junta rajadas de eventos e pede
 * um rescan; o próprio rescan descobre se mudou algo, então as escritas da API
 * não geram aviso duplicado.
 */
@Component
class CampanhaWatcher(
    private val files: CampanhaFileService,
    private val service: CampanhaService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var watch: WatchService? = null
    private val agenda = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "campanha-rescan").apply { isDaemon = true } }
    private var pendente: ScheduledFuture<*>? = null

    @EventListener(ApplicationReadyEvent::class)
    fun iniciar() {
        val ws = FileSystems.getDefault().newWatchService()
        watch = ws
        registrar(ws, files.raiz)
        Files.list(files.raiz).use { s -> s.filter { Files.isDirectory(it) }.forEach { registrar(ws, it) } }
        Thread({ loop(ws) }, "campanha-watcher").apply { isDaemon = true }.start()
        log.info("Campanha: observando {}", files.raiz)
    }

    private fun registrar(ws: WatchService, dir: Path) {
        dir.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
    }

    private fun loop(ws: WatchService) {
        while (true) {
            val key = try { ws.take() } catch (_: ClosedWatchServiceException) { return } catch (_: InterruptedException) { return }
            val dir = key.watchable() as Path
            var relevante = false
            for (ev in key.pollEvents()) {
                val nome = (ev.context() as? Path)?.toString() ?: continue
                if (nome.startsWith(".") || nome == CampanhaEstadoService.ARQUIVO || nome == CampanhaGrafoService.ARQUIVO || nome == "_referencias.md") continue
                val caminho = dir.resolve(nome)
                if (ev.kind() == ENTRY_CREATE && Files.isDirectory(caminho)) registrar(ws, caminho)
                relevante = true
            }
            key.reset()
            if (relevante) agendar()
        }
    }

    @Synchronized
    private fun agendar() {
        pendente?.cancel(false)
        pendente = agenda.schedule({
            try { service.pastaMudou() } catch (e: Exception) { log.warn("Campanha: rescan falhou: {}", e.message) }
        }, 300, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun parar() {
        watch?.close()
        agenda.shutdownNow()
    }
}

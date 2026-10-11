package com.rpg.essentia.controller

import com.rpg.essentia.model.DocumentCreateRequest
import com.rpg.essentia.model.DocumentUpdateRequest
import com.rpg.essentia.model.GameDocument
import com.rpg.essentia.service.DocumentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/documents")
class DocumentController(private val documentService: DocumentService) {

    @GetMapping
    fun getDocuments(): List<GameDocument> = documentService.getDocuments()

    @PostMapping
    fun createDocument(@RequestBody req: DocumentCreateRequest): GameDocument =
        documentService.createDocument(req.url, req.title)

    @PutMapping("/{id}")
    fun updateDocument(@PathVariable id: String, @RequestBody req: DocumentUpdateRequest): GameDocument =
        documentService.updateDocument(id, req.url, req.title)

    @PutMapping("/{id}/toggle")
    fun toggleDocument(@PathVariable id: String): List<GameDocument> =
        documentService.toggleDocument(id)

    @DeleteMapping("/{id}")
    fun deleteDocument(@PathVariable id: String): ResponseEntity<Void> {
        documentService.deleteDocument(id)
        return ResponseEntity.noContent().build()
    }
}

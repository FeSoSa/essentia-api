package com.rpg.essentia.controller

import com.rpg.essentia.model.FastAction
import com.rpg.essentia.model.VoteRequest
import com.rpg.essentia.service.FastActionService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/fast-action")
class FastActionController(private val fastActionService: FastActionService) {

    @GetMapping
    fun getFastAction(): FastAction = fastActionService.getFastAction()

    @PutMapping
    fun setFastAction(@RequestBody fastAction: FastAction): FastAction =
        fastActionService.setFastAction(fastAction)

    @PostMapping("/vote")
    fun vote(@RequestBody req: VoteRequest): FastAction =
        fastActionService.vote(req.playerId, req.optionId)

    @DeleteMapping
    fun deleteFastAction(): FastAction = fastActionService.deleteFastAction()
}

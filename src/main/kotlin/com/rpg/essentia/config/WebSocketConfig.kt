package com.rpg.essentia.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.support.DefaultHandshakeHandler

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {

    private val log = LoggerFactory.getLogger(WebSocketConfig::class.java)

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(object : ChannelInterceptor {
            override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
                val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                if (accessor != null) {
                    log.info("[inbound] command={} session={} destination={}",
                        accessor.command, accessor.sessionId, accessor.destination)
                }
                return message
            }
        })
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS()

        registry.addEndpoint("/ws-native")
            .setAllowedOriginPatterns("*")
            .setHandshakeHandler(object : DefaultHandshakeHandler() {
                override fun filterRequestedExtensions(
                    request: ServerHttpRequest,
                    requestedExtensions: List<WebSocketExtension>,
                    supportedExtensions: List<WebSocketExtension>
                ): List<WebSocketExtension> = emptyList()
            })
            .addInterceptors(object : HandshakeInterceptor {
                override fun beforeHandshake(
                    request: ServerHttpRequest, response: ServerHttpResponse,
                    wsHandler: WebSocketHandler, attributes: MutableMap<String, Any>
                ): Boolean {
                    log.info("[ws-native] handshake from {} headers={}", request.remoteAddress, request.headers)
                    return true
                }
                override fun afterHandshake(
                    request: ServerHttpRequest, response: ServerHttpResponse,
                    wsHandler: WebSocketHandler, ex: Exception?
                ) {
                    log.info("[ws-native] handshake complete, error={}", ex?.message)
                }
            })
    }
}

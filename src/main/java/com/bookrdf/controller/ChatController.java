package com.bookrdf.controller;

import com.bookrdf.service.ChatService;
import com.bookrdf.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final VectorStoreService vectorStoreService;

    public ChatController(ChatService chatService, VectorStoreService vectorStoreService) {
        this.chatService = chatService;
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String pageContext = body.getOrDefault("pageContext", "");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        String response = chatService.chat(message, pageContext);
        return ResponseEntity.ok(Map.of("response", response));
    }

    @GetMapping("/starters")
    public ResponseEntity<List<String>> getStarters(@RequestParam(defaultValue = "") String context) {
        List<String> starters = chatService.getConversationStarters(context);
        return ResponseEntity.ok(starters);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, String>> reindex() {
        vectorStoreService.rebuildIndex();
        return ResponseEntity.ok(Map.of("message", "Vector store reindexed"));
    }
}

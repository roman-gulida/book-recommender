package com.bookrdf.controller;

import com.bookrdf.service.RdfService;
import org.apache.jena.rdf.model.Model;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RdfController {

    private final RdfService rdfService;

    public RdfController(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    // Upload & Visualize RDF
    @PostMapping("/rdf/upload")
    public ResponseEntity<Map<String, Object>> uploadRdf(@RequestParam("file") MultipartFile file) {
        try {
            InputStream is = file.getInputStream();
            Model uploadedModel = rdfService.parseRdfFile(is);
            Map<String, Object> graph = rdfService.modelToGraph(uploadedModel);
            return ResponseEntity.ok(graph);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/rdf/graph")
    public ResponseEntity<Map<String, Object>> getCurrentGraph() {
        Map<String, Object> graph = rdfService.modelToGraph(rdfService.getModel());
        return ResponseEntity.ok(graph);
    }

    // Add / Modify Books
    @PostMapping("/books")
    public ResponseEntity<Map<String, String>> addBook(@RequestBody Map<String, Object> body) {
        String title = (String) body.get("title");
        String author = (String) body.get("author");
        @SuppressWarnings("unchecked")
        List<String> themes = (List<String>) body.get("themes");
        String readingLevel = (String) body.get("readingLevel");

        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }

        rdfService.addBook(title, author, themes != null ? themes : List.of(), readingLevel);
        return ResponseEntity.ok(Map.of("message", "Book '" + title + "' added successfully"));
    }

    @PutMapping("/books/{bookId}")
    public ResponseEntity<Map<String, String>> updateBook(
            @PathVariable String bookId,
            @RequestBody Map<String, String> body) {
        String newLevel = body.get("readingLevel");
        if (newLevel == null || newLevel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reading level is required"));
        }

        boolean updated = rdfService.updateBookLevel(bookId, newLevel);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Book updated successfully"));
    }

    // List Books & Book Detail
    @GetMapping("/books")
    public ResponseEntity<List<Map<String, String>>> listBooks() {
        return ResponseEntity.ok(rdfService.getAllBooks());
    }

    @GetMapping("/books/{bookId}")
    public ResponseEntity<?> getBook(@PathVariable String bookId) {
        Map<String, String> book = rdfService.getBookById(bookId);
        if (book == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(book);
    }
}

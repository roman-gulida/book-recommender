package com.bookrdf.service;

import com.google.gson.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ChatService {

    private final VectorStoreService vectorStoreService;
    private final RdfService rdfService;

    @Value("${llm.api.url}")
    private String apiUrl;

    @Value("${llm.api.key}")
    private String apiKey;

    @Value("${llm.model}")
    private String modelName;

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public ChatService(VectorStoreService vectorStoreService, RdfService rdfService) {
        this.vectorStoreService = vectorStoreService;
        this.rdfService = rdfService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // Process a chat message using RAG: retrieve relevant docs, then query LLM
    public String chat(String userMessage, String pageContext) {
        // Retrieve relevant documents from vector store
        List<String> relevantDocs = vectorStoreService.search(userMessage, 5);

        // Build context prompt
        StringBuilder context = new StringBuilder();
        context.append("You are a helpful book recommendation assistant. ");
        context.append("Answer questions ONLY based on the following book database information. ");
        context.append("If the information is not in the database, say so.\n\n");
        context.append("DATABASE INFORMATION \n");
        for (String doc : relevantDocs) {
            context.append("- ").append(doc).append("\n");
        }
        context.append("END DATABASE\n\n");

        if (pageContext != null && !pageContext.isEmpty()) {
            context.append("The user is currently viewing: ").append(pageContext).append("\n\n");
        }

        context.append("User question: ").append(userMessage);

        return callLlm(context.toString());
    }

    // Generate context-aware conversation starters
    public List<String> getConversationStarters(String pageContext) {
        if (pageContext == null || pageContext.isEmpty() || pageContext.equals("home")) {
            return List.of(
                    "What books are available in the database?",
                    "Can you recommend a book for a beginner?",
                    "What themes of books do you have?");
        }

        if (pageContext.equals("books_list")) {
            // Get actual book titles for context-aware starters
            List<Map<String, String>> books = rdfService.getAllBooks();
            if (!books.isEmpty()) {
                String firstBook = books.get(0).get("title");
                return List.of(
                        "What is a book that I am most likely to enjoy from this list?",
                        "Tell me more about " + firstBook,
                        "Which books are suitable for beginners?");
            }
        }

        if (pageContext.startsWith("book_")) {
            String bookId = pageContext.substring(5);
            Map<String, String> book = rdfService.getBookById(bookId);
            if (book != null) {
                String title = book.get("title");
                String author = book.get("author");
                String themes = book.get("themes");
                return List.of(
                        "Tell me more about " + title,
                        "What other books are written by " + author + "?",
                        "What other " + themes.split(",")[0].trim() + " books do you have?");
            }
        }

        return List.of(
                "What books are available?",
                "Can you recommend a book?",
                "What themes do you have?");
    }

    private String callLlm(String prompt) {
        // Build Google AI Studio request body
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);

        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject content = new JsonObject();
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", 500);
        generationConfig.addProperty("temperature", 0.3);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contents);
        requestBody.add("generationConfig", generationConfig);

        String url = apiUrl + "/" + modelName + ":generateContent?key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        gson.toJson(requestBody),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                return "Error calling LLM API (HTTP " + response.code() + "): " + errorBody;
            }

            String responseBody = response.body().string();
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates != null && candidates.size() > 0) {
                return candidates.get(0).getAsJsonObject()
                        .getAsJsonObject("content")
                        .getAsJsonArray("parts")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString();
            }
            return "No response from LLM.";
        } catch (IOException e) {
            return "Error communicating with LLM: " + e.getMessage();
        }
    }
}

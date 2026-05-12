package com.bookrdf.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorStoreService {
    private final RdfService rdfService;
    private List<String> documents = new ArrayList<>();
    private List<Map<String, Double>> tfidfVectors = new ArrayList<>();
    private Map<String, Double> idfMap = new HashMap<>();
    private Set<String> vocabulary = new HashSet<>();

    public VectorStoreService(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    @PostConstruct
    public void init() {
        rebuildIndex();
    }

    // Rebuild the vector index from the current RDF model
    public void rebuildIndex() {
        documents = rdfService.getAllDataAsDocuments();
        if (documents.isEmpty())
            return;

        vocabulary.clear();
        idfMap.clear();
        tfidfVectors.clear();

        // Tokenize all docs
        List<List<String>> tokenizedDocs = documents.stream()
                .map(this::tokenize)
                .collect(Collectors.toList());

        // Build vocabulary
        tokenizedDocs.forEach(tokens -> vocabulary.addAll(tokens));

        // Compute IDF
        int n = tokenizedDocs.size();
        for (String term : vocabulary) {
            long docCount = tokenizedDocs.stream()
                    .filter(tokens -> tokens.contains(term))
                    .count();
            idfMap.put(term, Math.log((double) n / (1 + docCount)) + 1);
        }

        // Compute vectors
        for (List<String> tokens : tokenizedDocs) {
            Map<String, Long> tf = tokens.stream()
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
            Map<String, Double> vector = new HashMap<>();
            for (Map.Entry<String, Long> entry : tf.entrySet()) {
                double tfidf = entry.getValue() * idfMap.getOrDefault(entry.getKey(), 0.0);
                vector.put(entry.getKey(), tfidf);
            }
            tfidfVectors.add(vector);
        }
    }

    // Search for the most relevant documents for a query
    public List<String> search(String query, int topK) {
        if (documents.isEmpty())
            return List.of();

        List<String> queryTokens = tokenize(query);
        Map<String, Long> queryTf = queryTokens.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        Map<String, Double> queryVector = new HashMap<>();
        for (Map.Entry<String, Long> entry : queryTf.entrySet()) {
            double tfidf = entry.getValue() * idfMap.getOrDefault(entry.getKey(), 0.0);
            queryVector.put(entry.getKey(), tfidf);
        }

        // Compute cosine similarity
        List<double[]> scores = new ArrayList<>();
        for (int i = 0; i < tfidfVectors.size(); i++) {
            double sim = cosineSimilarity(queryVector, tfidfVectors.get(i));
            scores.add(new double[] { i, sim });
        }

        // Sort by similarity descending
        scores.sort((a, b) -> Double.compare(b[1], a[1]));

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scores.size()); i++) {
            int idx = (int) scores.get(i)[0];
            if (scores.get(i)[1] > 0) {
                results.add(documents.get(idx));
            }
        }
        return results;
    }

    private List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-zA-Z0-9\\s]", " ").split("\\s+"))
                .filter(s -> !s.isBlank() && s.length() > 1)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        double dotProduct = 0, normA = 0, normB = 0;
        for (Map.Entry<String, Double> entry : a.entrySet()) {
            double bVal = b.getOrDefault(entry.getKey(), 0.0);
            dotProduct += entry.getValue() * bVal;
            normA += entry.getValue() * entry.getValue();
        }
        for (double val : b.values()) {
            normB += val * val;
        }
        if (normA == 0 || normB == 0)
            return 0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

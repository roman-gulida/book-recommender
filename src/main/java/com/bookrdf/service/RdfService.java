package com.bookrdf.service;

import jakarta.annotation.PostConstruct;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.query.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Service
public class RdfService {

    private static final String BOOK_NS = "http://example.org/book#";
    private static final String USER_NS = "http://example.org/user#";

    @Value("${rdf.file.path}")
    private String rdfFilePath;

    private Model model;

    @PostConstruct
    public void init() {
        model = ModelFactory.createDefaultModel();
        File rdfFile = new File(rdfFilePath);
        if (rdfFile.exists()) {
            try (InputStream in = new FileInputStream(rdfFile)) {
                model.read(in, null, "RDF/XML");
            } catch (IOException e) {
                throw new RuntimeException("Failed to load RDF file: " + rdfFilePath, e);
            }
        }
    }

    public Model getModel() {
        return model;
    }

    // Parse an uploaded RDF/XML file and return the model
    public Model parseRdfFile(InputStream inputStream) {
        Model uploadedModel = ModelFactory.createDefaultModel();
        uploadedModel.read(inputStream, null, "RDF/XML");
        return uploadedModel;
    }

    // Convert a Jena Model to a graph structure
    public Map<String, Object> modelToGraph(Model m) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();

        int edgeId = 0;
        StmtIterator iter = m.listStatements();
        while (iter.hasNext()) {
            Statement stmt = iter.nextStatement();
            Resource subject = stmt.getSubject();
            Property predicate = stmt.getPredicate();
            RDFNode object = stmt.getObject();

            String subjectId = subject.getURI() != null ? subject.getURI() : subject.toString();
            String subjectLabel = getLabel(m, subject);

            if (!nodeIds.contains(subjectId)) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", subjectId);
                node.put("label", subjectLabel);
                node.put("group", getNodeGroup(m, subject));
                nodes.add(node);
                nodeIds.add(subjectId);
            }

            String objectId;
            String objectLabel;
            if (object.isResource()) {
                Resource objRes = object.asResource();
                objectId = objRes.getURI() != null ? objRes.getURI() : objRes.toString();
                objectLabel = getLabel(m, objRes);
            } else {
                objectId = "literal_" + object.toString().hashCode();
                objectLabel = object.toString();
            }

            if (!nodeIds.contains(objectId)) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", objectId);
                node.put("label", objectLabel);
                if (object.isLiteral()) {
                    node.put("group", "literal");
                } else {
                    node.put("group", getNodeGroup(m, object.asResource()));
                }
                nodes.add(node);
                nodeIds.add(objectId);
            }

            String predicateLabel = predicate.getLocalName();
            Map<String, Object> edge = new HashMap<>();
            edge.put("id", edgeId++);
            edge.put("from", subjectId);
            edge.put("to", objectId);
            edge.put("label", predicateLabel);
            edges.add(edge);
        }

        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private String getLabel(Model m, Resource resource) {
        Statement labelStmt = resource.getProperty(RDFS.label);
        if (labelStmt != null) {
            return labelStmt.getString();
        }
        if (resource.getURI() != null) {
            String uri = resource.getURI();
            int idx = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
            return idx >= 0 ? uri.substring(idx + 1) : uri;
        }
        return resource.toString();
    }

    private String getNodeGroup(Model m, Resource resource) {
        if (resource.getURI() == null)
            return "other";
        String uri = resource.getURI();
        if (uri.startsWith(BOOK_NS)) {
            // Check if it's a Book, Theme, or ReadingLevel
            Resource bookClass = m.getResource(BOOK_NS + "Book");
            Resource themeClass = m.getResource(BOOK_NS + "Theme");
            Resource levelClass = m.getResource(BOOK_NS + "ReadingLevel");

            if (resource.hasProperty(
                    m.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), bookClass)) {
                return "book";
            }
            if (resource.hasProperty(
                    m.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), themeClass)) {
                return "theme";
            }
            if (resource.hasProperty(
                    m.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), levelClass)) {
                return "level";
            }
        }
        if (uri.startsWith(USER_NS))
            return "user";
        return "other";
    }

    // Get all books from the model using SPARQL
    public List<Map<String, String>> getAllBooks() {
        String queryStr = "PREFIX book: <" + BOOK_NS + ">\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "SELECT ?bookUri ?title ?author ?level (GROUP_CONCAT(?themeName; SEPARATOR=', ') AS ?themes)\n" +
                "WHERE {\n" +
                "  ?bookUri a book:Book .\n" +
                "  ?bookUri rdfs:label ?title .\n" +
                "  OPTIONAL { ?bookUri book:hasAuthor ?author }\n" +
                "  OPTIONAL { ?bookUri book:hasReadingLevel ?levelUri . ?levelUri rdfs:label ?level }\n" +
                "  OPTIONAL { ?bookUri book:hasTheme ?themeUri . ?themeUri rdfs:label ?themeName }\n" +
                "}\n" +
                "GROUP BY ?bookUri ?title ?author ?level\n" +
                "ORDER BY ?title";

        List<Map<String, String>> books = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(queryStr), model)) {
            ResultSet results = qe.execSelect();
            while (results.hasNext()) {
                QuerySolution sol = results.nextSolution();
                Map<String, String> book = new HashMap<>();
                book.put("uri", sol.getResource("bookUri").getURI());
                book.put("title", sol.getLiteral("title").getString());
                book.put("author", sol.contains("author") ? sol.getLiteral("author").getString() : "Unknown");
                book.put("level", sol.contains("level") ? sol.getLiteral("level").getString() : "Unknown");
                book.put("themes", sol.contains("themes") ? sol.getLiteral("themes").getString() : "");
                // Extract the book ID from URI
                String uri = sol.getResource("bookUri").getURI();
                book.put("id", uri.substring(uri.lastIndexOf('#') + 1));
                books.add(book);
            }
        }
        return books;
    }

    // Get a single book by its ID (local name from URI)
    public Map<String, String> getBookById(String bookId) {
        String bookUri = BOOK_NS + bookId;
        Resource bookRes = model.getResource(bookUri);

        // Check if this resource is typed as a Book
        Resource bookClass = model.getResource(BOOK_NS + "Book");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        if (!bookRes.hasProperty(rdfType, bookClass)) {
            return null;
        }

        Map<String, String> book = new HashMap<>();
        book.put("uri", bookUri);
        book.put("id", bookId);

        Statement labelStmt = bookRes.getProperty(RDFS.label);
        book.put("title", labelStmt != null ? labelStmt.getString() : bookId);

        Property hasAuthor = model.getProperty(BOOK_NS + "hasAuthor");
        Statement authorStmt = bookRes.getProperty(hasAuthor);
        book.put("author", authorStmt != null ? authorStmt.getString() : "Unknown");

        Property hasLevel = model.getProperty(BOOK_NS + "hasReadingLevel");
        Statement levelStmt = bookRes.getProperty(hasLevel);
        if (levelStmt != null && levelStmt.getObject().isResource()) {
            Statement levelLabel = levelStmt.getResource().getProperty(RDFS.label);
            book.put("level", levelLabel != null ? levelLabel.getString() : "Unknown");
        } else {
            book.put("level", "Unknown");
        }

        Property hasTheme = model.getProperty(BOOK_NS + "hasTheme");
        StmtIterator themeIter = bookRes.listProperties(hasTheme);
        List<String> themes = new ArrayList<>();
        while (themeIter.hasNext()) {
            Statement themeStmt = themeIter.next();
            if (themeStmt.getObject().isResource()) {
                Statement themeLabel = themeStmt.getResource().getProperty(RDFS.label);
                themes.add(themeLabel != null ? themeLabel.getString() : themeStmt.getResource().getLocalName());
            }
        }
        book.put("themes", String.join(", ", themes));

        return book;
    }

    // Add a new book to the model and persist
    public void addBook(String title, String author, List<String> themes, String readingLevel) {
        String bookId = title.replaceAll("[^a-zA-Z0-9]", "");
        String bookUri = BOOK_NS + bookId;

        Resource bookClass = model.getResource(BOOK_NS + "Book");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Property hasAuthor = model.getProperty(BOOK_NS + "hasAuthor");
        Property hasTheme = model.getProperty(BOOK_NS + "hasTheme");
        Property hasLevel = model.getProperty(BOOK_NS + "hasReadingLevel");

        Resource bookRes = model.createResource(bookUri);
        bookRes.addProperty(rdfType, bookClass);
        bookRes.addProperty(RDFS.label, title);
        if (author != null && !author.isEmpty()) {
            bookRes.addProperty(hasAuthor, author);
        }

        for (String theme : themes) {
            String themeId = theme.replaceAll("[^a-zA-Z0-9]", "");
            Resource themeRes = model.getResource(BOOK_NS + themeId);
            // Ensure the theme resource exists
            if (!model.contains(themeRes, rdfType)) {
                Resource themeClass = model.getResource(BOOK_NS + "Theme");
                themeRes.addProperty(rdfType, themeClass);
                themeRes.addProperty(RDFS.label, theme);
            }
            bookRes.addProperty(hasTheme, themeRes);
        }

        if (readingLevel != null && !readingLevel.isEmpty()) {
            String levelId = readingLevel.replaceAll("[^a-zA-Z0-9]", "");
            Resource levelRes = model.getResource(BOOK_NS + levelId);
            bookRes.addProperty(hasLevel, levelRes);
        }

        saveModel();
    }

    // Update a book's reading level
    public boolean updateBookLevel(String bookId, String newLevel) {
        String bookUri = BOOK_NS + bookId;
        Resource bookRes = model.getResource(bookUri);

        Resource bookClass = model.getResource(BOOK_NS + "Book");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        if (!bookRes.hasProperty(rdfType, bookClass)) {
            return false;
        }

        Property hasLevel = model.getProperty(BOOK_NS + "hasReadingLevel");
        // Remove existing level
        bookRes.removeAll(hasLevel);
        // Add new level
        String levelId = newLevel.replaceAll("[^a-zA-Z0-9]", "");
        Resource levelRes = model.getResource(BOOK_NS + levelId);
        bookRes.addProperty(hasLevel, levelRes);

        saveModel();
        return true;
    }

    // Get all users from the model
    public List<Map<String, String>> getAllUsers() {
        String queryStr = "PREFIX user: <" + USER_NS + ">\n" +
                "PREFIX book: <" + BOOK_NS + ">\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "SELECT ?userUri ?name ?level (GROUP_CONCAT(?themeName; SEPARATOR=', ') AS ?themes)\n" +
                "WHERE {\n" +
                "  ?userUri a user:User .\n" +
                "  ?userUri rdfs:label ?name .\n" +
                "  OPTIONAL { ?userUri user:hasReadingLevel ?levelUri . ?levelUri rdfs:label ?level }\n" +
                "  OPTIONAL { ?userUri user:prefersTheme ?themeUri . ?themeUri rdfs:label ?themeName }\n" +
                "}\n" +
                "GROUP BY ?userUri ?name ?level\n" +
                "ORDER BY ?name";

        List<Map<String, String>> users = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(queryStr), model)) {
            ResultSet results = qe.execSelect();
            while (results.hasNext()) {
                QuerySolution sol = results.nextSolution();
                Map<String, String> user = new HashMap<>();
                user.put("name", sol.getLiteral("name").getString());
                user.put("level", sol.contains("level") ? sol.getLiteral("level").getString() : "Unknown");
                user.put("themes", sol.contains("themes") ? sol.getLiteral("themes").getString() : "");
                users.add(user);
            }
        }
        return users;
    }

    // Get all data as text for vector store indexing
    public List<String> getAllDataAsDocuments() {
        List<String> docs = new ArrayList<>();

        // Books
        for (Map<String, String> book : getAllBooks()) {
            String doc = String.format(
                    "Book: %s. Author: %s. Themes: %s. Reading Level: %s.",
                    book.get("title"), book.get("author"), book.get("themes"), book.get("level"));
            docs.add(doc);
        }

        // Users
        for (Map<String, String> user : getAllUsers()) {
            String doc = String.format(
                    "User: %s. Preferred themes: %s. Reading level: %s.",
                    user.get("name"), user.get("themes"), user.get("level"));
            docs.add(doc);
        }

        return docs;
    }

    private void saveModel() {
        try (OutputStream out = new FileOutputStream(rdfFilePath)) {
            model.write(out, "RDF/XML");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save RDF model", e);
        }
    }
}

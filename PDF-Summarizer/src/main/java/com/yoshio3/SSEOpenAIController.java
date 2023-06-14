package com.yoshio3;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatMessageDelta;
import com.azure.ai.openai.models.ChatRole;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.google.gson.Gson;
import com.yoshio3.entities.CreateAreaInHTML;
import com.yoshio3.entities.CreateLinkInHTML;
import com.yoshio3.entities.CreateMessageInHTML;
import com.yoshio3.entities.DocumentSummarizer;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

@Controller
@Component
public class SSEOpenAIController {

    private final Logger LOGGER = LoggerFactory.getLogger(SSEOpenAIController.class);

    // Azure OpenAI Instance URL
    @Value("${azure.openai.url}")
    private String OPENAI_URL;

    // Name of Model
    @Value("${azure.openai.model.name}")
    private String OPENAI_MODEL_NAME;

    // Azure OpenAI API Key
    @Value("${azure.openai.api.key}")
    private String OPENAI_API_KEY;

    @Value("${azure.postgresql.jdbcurl}")
    private String POSTGRESQL_JDBC_URL;

    @Value("${azure.postgresql.user}")
    private String POSTGRESQL_USER;

    @Value("${azure.postgresql.password}")
    private String POSTGRESQL_PASSWORD;

    @Value("${azure.postgresql.db.table.name}")
    private String POSTGRESQL_TABLE_NAME;

    @Value("${azure.blobstorage.name}")
    private String BLOB_STORAGE_NAME;

    @Value("${azure.blobstorage.container.name}")
    private String BLOB_STORAGE_CONTAINER_NAME;

    // Maximum number of results to be returned by the search process
    private static final int MAX_RESULT = 5;

    private static final String TEXT_EMBEDDING_ADA = "text-embedding-ada-002";

    private final static String SYSTEM_DEFINITION = """
                    This system is designed for managing documents. 
                    It searches for documents that match the content entered by users, summarizes them, 
                    and provides the summarized information to the users in an easily understandable and polite manner.
            """;

    // Sinks for accepting requests from clients (Sinks for sending and receiving one-to-one)
    private static Map<UUID, Sinks.Many<String>> userSinks;

    // Static Initializer
    static {
        userSinks = new ConcurrentHashMap<>();
    }

    @Autowired
    private CosmosDBUtil cosmosDBUtil;

    private OpenAIAsyncClient client;

    @PostConstruct
    public void init() {
        client = new OpenAIClientBuilder().endpoint(OPENAI_URL)
                .credential(new AzureKeyCredential(OPENAI_API_KEY)).buildAsyncClient();
    }

    // Return index.html
    @GetMapping("/")
    public String index() {
        return "index";
    }

    // When accessing the page, create a UUID for each client (for 1-on-1 sending
    // and receiving)
    // This part of the process is unnecessary if you want to update the same
    // content (1-to-many) like a chat
    @GetMapping(path = "/openai-gpt4-sse-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<String> sseStream(@RequestParam UUID userId) {
        Sinks.Many<String> userSink = getUserSink(userId);
        if (userSink == null) {
            userSink = createUserSink(userId);
        }
        LOGGER.trace("USER ID IS ADDED: {}}", userId);
        return userSink.asFlux().delayElements(Duration.ofMillis(10));
    }

    @PostMapping("/openai-gpt4-sse-submit")
    @ResponseBody
    public void openaiGpt4Sse(@RequestBody String inputText, @RequestParam UUID userId) {
        var userSink = getUserSink(userId);
        LOGGER.debug("InputText --------------: {}", inputText);
        // Receive input from the user and search for documents from PostgreSQL's Vector DB
        findMostSimilarString(inputText).subscribe(findMostSimilarString -> {
            // Based on document search results,invoke OpenAI to summarizes and sends results to the client
            findMostSimilarString.forEach(docSummary -> {
                requestOpenAIToGetSummaryAndSendMessageToClient(docSummary, inputText, userSink);
            });
        });
    }

    // Create a message to send to chat
    private String createChatMessages(DocumentSummarizer docSummary, String inputText) {
        return String.format(
                "\"\"\" %s \"\"\" \n\nFrom the above document \"%s\" Please extract the part that describes.",
                docSummary.origntext(), inputText);
    }

    // Create a document summary of the search results sent to OpenAI and send it to the client via Stream
    private void requestOpenAIToGetSummaryAndSendMessageToClient(DocumentSummarizer docSummary, String inputText, Sinks.Many<String> userSink) {
        LOGGER.debug("Origin --------------: {}", docSummary.origntext());

        var input = createChatMessages(docSummary, inputText);
        LOGGER.debug("User Sink: {}", userSink);
        LOGGER.debug(input);
        var chatMessages = createMessages(input);
        LOGGER.debug("OpenAI Model : {}", OPENAI_MODEL_NAME);

        // Send a request to OpenAI and send the result to the client
        client.getChatCompletionsStream(OPENAI_MODEL_NAME, new ChatCompletionsOptions(chatMessages))
                .doOnSubscribe(subscription -> {
                    // Send a request event to create a DIV area in HTML to display the link and the resulting string
                    sendCreateAreaEvent(userSink, docSummary);
                    // Send a request event to create a link in HTML to display the link
                    sendCreateLinkEvent(userSink, docSummary);
                })
                .subscribe(chatCompletions -> {
                    // Send the result string from OpenAI to Client via Streaming
                    sendChatCompletionMessages(userSink, docSummary, chatCompletions, inputText);
                }, error -> {
                    LOGGER.error("Error Occurred: {}", error.getMessage());
                    userSink.tryEmitError(error);
                }, () -> {
                    LOGGER.debug("Completed");
                });
    }

    // Send a request event to create a DIV area in HTML to display the link and the resulting string
    private void sendCreateAreaEvent(Sinks.Many<String> userSink, DocumentSummarizer docSummary) {
        var documentID = docSummary.id().toString();
        var createArea = new CreateAreaInHTML("create", documentID);
        var gson = new Gson();
        var jsonCreateArea = gson.toJson(createArea);
        LOGGER.debug("jsonCreateArea: {}", jsonCreateArea);
        userSink.tryEmitNext(jsonCreateArea);
        // wait few mill seconds
        intervalToSendClient();
    }

    // Send a request event to create a link in HTML to display the link
    private void sendCreateLinkEvent(Sinks.Many<String> userSink, DocumentSummarizer docSummary) {
        var fileName = docSummary.filename();
        var pageNumber = docSummary.pageNumber();
        var documentID = docSummary.id().toString();
        // Create URL for Blob Storage
        var URL = "https://" + BLOB_STORAGE_NAME + ".blob.core.windows.net/"
                + BLOB_STORAGE_CONTAINER_NAME + "/" + fileName + "#page="
                + docSummary.pageNumber();

        var createLinkRecord = new CreateLinkInHTML("createLink", documentID, URL, pageNumber, fileName);
        var gson = new Gson();
        var jsonLink = gson.toJson(createLinkRecord);
        LOGGER.debug("JSON Create Link: {}", jsonLink);
        userSink.tryEmitNext(jsonLink);
        // wait few mill seconds
        intervalToSendClient();
    }

    // Send a request event to display the message character by character in the HTML
    private void sendChatCompletionMessages(Sinks.Many<String> userSink, DocumentSummarizer docSummary,
            ChatCompletions chatCompletions, String inputText) {
        var documentID = docSummary.id().toString();

        chatCompletions.getChoices().stream().map(ChatChoice::getDelta)
                .map(ChatMessageDelta::getContent)
                .filter(content -> content != null)
                .forEach(content -> {
                    if (content.contains(" ")) {
                        content = content.replace(" ", "<SPECIAL_WHITE_SPACE>");
                    }
                    LOGGER.debug(content);
                    var createMessage = new CreateMessageInHTML("addMessage", documentID, content);
                    var gson = new Gson();
                    var jsonMessage = gson.toJson(createMessage);
                    LOGGER.debug("JSON Message: {}", jsonMessage);
                    var result = userSink.tryEmitNext(jsonMessage);
                    showDetailErrorReasonForSSE(result, content, inputText);
                    // wait few mill seconds
                    intervalToSendClient();
                });
    }

    @GetMapping("/listAllRegisteredContents")
    public String listAllRegisteredContents(Model model) {
        // Get all documents from CosmosDB and add them to Model for display on a web page
        // Paging should be implemented in the future
        model.addAttribute("list", cosmosDBUtil.getAllRegisteredDocuments());
        return "listAllRegisteredContents";
    }

    @GetMapping("/listAllFailedContents")
    public String listAllFailedContents(Model model) {
        // Get all documents from CosmosDB and add them to Model for display on a web page
        // Paging should be implemented in the future
        model.addAttribute("list", cosmosDBUtil.getAllFailedDocuments());
        return "listAllFailedContents";
    }

    // Create Sinks for accessed User
    private Sinks.Many<String> createUserSink(UUID userId) {
        Sinks.Many<String> userSink = Sinks.many().multicast().directBestEffort();
        userSinks.put(userId, userSink);
        LOGGER.debug("User ID: {} User Sink: {} is Added.", userId, userSink);
        return userSink;
    }

    // Get User Sinks
    private Sinks.Many<String> getUserSink(UUID userId) {
        return userSinks.get(userId);
    }

    /**
     * Crete ChatMessage list
     */
    private List<ChatMessage> createMessages(String userInput) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatMessage(ChatRole.SYSTEM).setContent(SYSTEM_DEFINITION));
        chatMessages.add(new ChatMessage(ChatRole.USER).setContent(userInput));
        return chatMessages;
    }

    // Show Error Message when SSE failed to send the message
    private void showDetailErrorReasonForSSE(EmitResult result, String returnValue, String data) {
        if (result.isFailure()) {
            LOGGER.error("Failure: {}", returnValue + " " + data);
            if (result == EmitResult.FAIL_OVERFLOW) {
                LOGGER.error("Overflow: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_NON_SERIALIZED) {
                LOGGER.error("Non-serialized: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                LOGGER.error("Zero subscriber: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_TERMINATED) {
                LOGGER.error("Terminated: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_CANCELLED) {
                LOGGER.error("Cancelled: {}", returnValue + " " + data);
            }
        }
    }

    // Spring Data JPA is not available at this time
    // Reason: 
    // Spring Data JPA could not handle the PostgreSQL vector type, 
    // even if it used Native Query. So I used standard JDBC.
    public Mono<List<DocumentSummarizer>> findMostSimilarString(String inputData) {
        EmbeddingsOptions embeddingsOptions = new EmbeddingsOptions(Arrays.asList(inputData));

        return client.getEmbeddings(TEXT_EMBEDDING_ADA, embeddingsOptions)
                .flatMap(embeddings -> {
                    List<DocumentSummarizer> docSummaryList = new ArrayList<>();
                    List<Double> embedding = embeddings.getData().stream().findFirst().get().getEmbedding();

                    try (var connection = DriverManager.getConnection(POSTGRESQL_JDBC_URL,
                            POSTGRESQL_USER, POSTGRESQL_PASSWORD)) {
                        String array = embedding.toString();
                        LOGGER.debug("Embedding: \n{}", array);

                        String querySql = "SELECT id,origntext,filename,pageNumber FROM " + POSTGRESQL_TABLE_NAME
                                + " ORDER BY embedding <-> ?::vector LIMIT " + MAX_RESULT + ";";

                        PreparedStatement queryStatement = connection.prepareStatement(querySql);
                        queryStatement.setString(1, array);
                        ResultSet resultSet = queryStatement.executeQuery();
                        LOGGER.debug("resultSet: {}", resultSet);
                        while (resultSet.next()) {
                            DocumentSummarizer documentSummarizer = new DocumentSummarizer(
                                    UUID.fromString(resultSet.getString("id")),
                                    null,
                                    resultSet.getString("origntext"),
                                    resultSet.getString("filename"),
                                    resultSet.getInt("pageNumber"));
                            docSummaryList.add(documentSummarizer);
                            LOGGER.debug("DocumentSummarizer: {}", documentSummarizer);
                        }
                    } catch (SQLException e) {
                        LOGGER.error("Connection failure: {}", e.getMessage());
                    }
                    return Mono.just(docSummaryList);
                });
    }

    private void intervalToSendClient() {
        try {
            TimeUnit.MILLISECONDS.sleep(20);
        } catch (InterruptedException e) {
            LOGGER.error("Error Occurred: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
